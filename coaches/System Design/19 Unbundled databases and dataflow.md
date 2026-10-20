# 19 Unbundled databases and dataflow

## Opening prompt
"Our company runs a payments and accounts platform. Today the accounts database is the system of record, and the search index, the balance cache, the notification service, the fraud features and the warehouse are all fed by application code that writes to several places. They drift, and every new consumer means a new integration project. Two features hurt most: usernames must be unique, and money transfers between accounts must be applied exactly once even though they touch two accounts that live on different shards. We also want clients to see changes within a second. Design the data architecture so that all of this is built around dataflow, and tell me what you give up compared to one big transactional database."

## Hidden requirements
- Q: Which data is the system of record and which is derived? → A: Only the ordered log of requests (transfers, username claims, profile changes) is the source of truth. Balances, the search index, caches, notification state, fraud features and warehouse tables are derived, deterministic functions of the log and can be rebuilt by replaying it. [critical]
- Q: What are the scale parameters? → A: About 5K transfer requests per second on average and 20K at peak, about 500 bytes each. About 200 username claims per second on average and 2K at peak during a signup campaign. About 200M accounts. About 100K balance and history reads per second. About 2M clients connected for live updates. Five or more independent derived consumers. [critical]
- Q: Why not just write to all the systems from the application, or use one distributed transaction (two-phase commit) across them? → A: Dual writes race and leave permanent divergence after a partial failure (case 18). Heterogeneous distributed transactions block on coordinator failure, need every participant to support the protocol, and spread one component's failure to all callers. The candidate must name the alternative: one ordered log with idempotent consumers. [critical]
- Q: What does "exactly once" mean for a transfer? → A: The final effect on both accounts is the same as if no fault had occurred, even when the client retries after a timeout, the processor crashes and replays, or a consumer redelivers. The database or the broker alone cannot guarantee this end to end. The request needs a client-generated ID that travels from the client to the final write. [critical]
- Q: How do you enforce unique usernames without a single database? → A: Route every claim for a username to the same log partition (partition by hash of the username). A single-threaded processor owns that partition and a local table of taken names. It decides in log order, emits an accepted or rejected result to an output stream, and the client waits for its result. No multi-master. Uniqueness needs a total order on conflicting writes, and this is how the log provides it. [critical]
- Q: How does a transfer between two accounts on different partitions stay atomic without an atomic commit? → A: Step 1: append the transfer as one message in the request log (a single-object write is atomic: it is there or it is not). Step 2: a deterministic processor reads it and emits a debit instruction (keyed by payer account) and a credit instruction (keyed by payee account), both carrying the request ID. Step 3: per-account appliers deduplicate by request ID and apply. If step 2 crashes and replays, it emits the same instructions again and step 3 discards the duplicates. [critical]
- Q: What consistency does a client observe? → A: Integrity (no lost, duplicated or contradictory facts) must hold always. Timeliness (seeing the latest state) is eventual, typically within a second or two. A client must see its own writes: the write returns an offset or request ID, and reads can wait until the derived view has applied it. Violations of timeliness are "eventual consistency", violations of integrity are "perpetual inconsistency". [critical]
- Q: Which constraints need synchronous coordination and which can be loosely interpreted? → A: Account balance must not go negative: all debits of one account are on one partition, so the check is local and strict. Unique usernames are strict but scale by partitioning. Constraints that span partitions or whose violation can be repaired (stock, overbooked seats, a daily limit across accounts) may be checked after the fact with a compensating action if the business accepts the cost of an apology. This is a business decision, not a technical one.
- Q: How do events that happen in different services get ordered? → A: A single log partition gives a total order for one key, a single leader gives a total order for a small system, and nothing gives a global order across partitions or datacenters for free. Causal dependencies (unfriend then post, quote then buy) need explicit event IDs or logical timestamps that later events reference.
- Q: What about derived data that depends on time, such as an exchange rate? → A: The processor subscribes to the rate stream and keeps the current rate in local state (a stream-table join) instead of calling a rate service per transfer. On replay the join must use the rate as of the original event time, so the rate history has to be a versioned stream, not only the latest value (time dependence of joins).
- Q: How do you change a derived view or fix a bug in the derivation? → A: Reprocess the log into a new version of the view alongside the old one, shift a small share of readers to it, compare, and switch over gradually. The old view stays as a rollback. This is the same idea as the dual-gauge railway migration. Batch and stream use the same code and the same log; no separate lambda-style implementation.
- Q: How are reads handled? → A: Normal reads go to the derived stores (the boundary between write path and read path). Reads that influence a decision (the quote or balance the user saw before confirming) can be logged as events for causality and audit, at a cost. A client can also subscribe to changes (push) instead of polling.
- Q: How do you know the derived data is right? → A: You do not trust it blindly. Continuous end-to-end checks: recompute balances from the log and compare with the serving store, check that total debits equal total credits, compare counts between source and each derived system, and hash or sign the log to detect corruption. "Trust, but verify." [critical]
- Q: What failures must the design tolerate? → A: Processor crash and replay, broker partition leader failover, a slow or dead consumer, client retries, duplicate delivery, and one derived store being down for hours. A slow consumer must not slow producers or other consumers, and it must catch up from its offset without losing data.
- Q: Is there a privacy requirement? → A: Yes. Personal data must be erasable and must not be retained forever. An immutable log makes this hard: keep personal data out of events (reference by ID), or encrypt per user and delete the key (crypto-shredding), or compact and rewrite. A strong candidate also thinks about what the data is used for, not only how to store it.
- Q: When should this architecture not be used? → A: When a single database can do all of it. Unbundling gives breadth, not depth. It is justified only when no single product meets all requirements, and it adds operational parts, learning cost and weaker timeliness.

## Back-of-envelope
- Request log: 5K per second × 500 B = 2.5 MB per second on average, 20K per second × 500 B = 10 MB per second at peak. Per day 2.5 MB/s × 86,400 s is about 216 GB. Retention of 7 days is about 1.5 TB, about 4.5 TB with 3× replication. The full history kept in object storage is 216 GB × 365, about 79 TB per year before compression.
- Instruction streams: each transfer produces a debit and a credit of about 300 B. That is 10K messages per second on average (3 MB/s), 40K per second at peak (12 MB/s). Five independent consumers each reading the request log add about 5 × 2.5 = 12.5 MB/s of broker egress on average. Bandwidth is not the constraint; per-partition ordering and sink write rates are.
- Partitions: with 10–50 MB/s per partition as a rule of thumb (case 18), the peak 12 MB/s needs only a handful. The real constraint is consumer parallelism and later growth. 64 partitions give 40K / 64, about 625 messages per second per partition on average. Even with 10× skew that is about 6K per second, well within one single-threaded processor with a local store (assume 20K–50K simple operations per second). Partition count is hard to change later and, for uniqueness, changing it moves names between partitions, so choose with headroom.
- Hot account: if a merchant receives 20% of all credits at peak, that is 0.2 × 20K = 4K per second on one partition: fine. At 80% it is 16K per second, close to the limit of one single-threaded applier. Remedies: apply credits in batches (they commute), or split the hot account into sub-accounts (sharded counter) and read the sum, at the price of a more complex balance check.
- Username claims: 2K per second at peak over 32 partitions is about 62 per second per partition. State is 200M names × 50 B = 10 GB, about 0.3 GB per partition. If 10K people claim the same name in a campaign, all 10K messages land on one partition in order. At about 50 µs per local lookup that is about 0.5 s of work: one acceptance, 9,999 rejections.
- Claim latency: append with replica acknowledgement about 5–10 ms, processor poll and decision 5–20 ms, append of the result and delivery to the waiting client 5–20 ms. About 30–50 ms at the median and a few hundred ms at p99 (estimate), compared with about 5 ms for a unique index insert in one database. That is the price of the decoupled design: several times slower per call, but it scales by partitions and tolerates a dead consumer.
- Deduplication state: with request IDs of 16 B and a 24-hour dedup window, 5K × 86,400 = 432M IDs × 16 B, about 7 GB, about 0.1 GB per partition over 64. A 7-day window is about 3.0B IDs, 48 GB. Keeping IDs forever is 5K × 86,400 × 365 = 158B IDs × 16 B, about 2.5 TB per year for one leg alone. So dedup state lives in a window, and the validator rejects requests whose embedded timestamp is older than the window.
- Replay: a new derived store reading 7 days of requests (about 3.0B messages, 1.5 TB). Reading is fast: 64 partitions × 50 MB/s is about 3.2 GB per second, so about 470 s. Writing is the bottleneck: a sink that takes 100K writes per second needs 3.0B / 100K = 30,240 s, about 8.4 hours. Starting from a compacted topic of current state (200M accounts × 200 B = 40 GB, 200M rows) at 100K per second takes 2,000 s, about 33 minutes. This is why you snapshot plus replay the tail, throttle replays so they do not flood shared sinks, or build offline and swap (case 17).
- Availability of a synchronous chain: three stores at 99.9% each give 0.999³ ≈ 99.7%, which is about 26 hours of downtime per year, compared with 8.77 hours for one store (see the availability table in the numbers cheat sheet). A log-based design makes the producer depend only on the log, and a dead consumer simply falls behind.
- Cost of logging reads: 100K reads per second × 200 B = 20 MB per second, about 1.7 TB per day, which is 8 times the write log (216 GB per day). Log only decision-relevant reads (the quote shown before confirming, the balance shown before a transfer), not every list view.
- Live clients: 2M connections × about 20 KB of server memory is about 40 GB. At 100K connections per gateway that is 20 gateways, about 30 with headroom. A reconnect storm of 2M clients over 60 s is about 33K connection setups per second, which resume from a stored offset instead of re-reading state.
- Audit: a nightly check from the last verified snapshot plus the day's 216 GB of log is minutes of scanning. A full-history recomputation of 79 TB at an ideal 40 GB per second (200 nodes × 200 MB/s, case 17) is about 2,000 s, about 33 minutes, so a monthly full audit is realistic.
- Loose constraints: if 0.01% of 5K requests per second need a compensating action, that is 0.5 per second, about 43K per day. Whether that is acceptable is the cost of an apology times its rate, compared with the cost of the extra coordination and unavailability needed to prevent it.

## Reference design
**Core API.**
- POST /transfers (request_id, from_account, to_account, amount, currency) returns 202 with the request ID and a log offset. The server appends to the request log and does not wait for derived stores.
- GET /requests/{request_id} (or a subscription) returns accepted, rejected(reason) or applied, read from the result stream.
- POST /usernames/claim (request_id, username, user_id) returns the result by waiting on the output stream with a timeout, then falling back to polling the request ID.
- GET /accounts/{id}/balance?min_offset=N serves from the derived store and waits (up to a bound) until it has applied offset N. This gives read-your-writes.
- GET /accounts/{id}/events?from=<offset> over server-sent events or a WebSocket pushes state changes, resumable by offset.
- All write requests carry a client-generated request_id (UUID or hash of the form fields). Requests older than the dedup window are rejected.

**Data model.**
- Request log: partitioned by request_id, retained 7 days, then archived to object storage. Event: request_id, type, payload, client timestamp, schema version. Immutable.
- Username-claims log: partitioned by hash of the (normalized) username. Processor state: table username to owner.
- Instruction topics: debits keyed by payer account, credits keyed by payee account, each carrying request_id and leg.
- Ledger state per account partition: balance, last applied request IDs in the dedup window (or applied offset per input partition), version. Written atomically with the applied-offset checkpoint.
- Result stream: keyed by request_id (accepted, rejected, reason).
- Compacted topics: latest account state and latest username owners, for bootstrapping new consumers.
- Derived stores: search index, balance cache, notification state, fraud features, warehouse tables. Each keeps its source offset to ignore stale or duplicate input.
- Rate or reference streams (exchange rates) with history, for time-dependent joins.
- Audit data: periodic hash or signature over log segments, reconciliation reports.

**Components.**
- Edge API: validates the request, assigns or checks the request ID, appends to the log, optionally waits on the result stream.
- Partitioned, replicated log (Kafka-style): ordering per partition, durable, replayable, consumer offsets.
- Validator and splitter (stream processor): partitioned by payer account, checks balance, emits debit and credit instructions with the request ID. Deterministic, restartable from a checkpoint.
- Ledger appliers: partitioned by account, single-threaded per partition, local embedded store, deduplicate by request ID, update balance and checkpoint atomically.
- Username processor: partitioned by name, local table, emits accepted or rejected.
- Derived-view consumers: each independent, with its own offset, applies idempotently by key and version. A slow one does not slow the others.
- Push gateways: hold client connections, subscribe to per-account streams, resume by offset after disconnects.
- Reprocessing tooling: run a new version of a derivation from the log or from a snapshot plus tail into a new view, compare with the old, switch traffic gradually.
- Auditor: recomputes state from the log, compares with serving stores, checks the double-entry invariant, alerts on divergence.
- Operations: lag per consumer, end-to-end latency, dedup hit rate, rejections by reason, dead-letter volume, replay throttles.

**Key trade-offs and rejected alternatives.**
- One big transactional database: simplest and strongest. If one product covers all needs, use it. Unbundling is justified only when requirements exceed any single system (search, analytics, offline clients, very different access patterns), and it gives breadth, not depth.
- Distributed transactions (XA, two-phase commit) across heterogeneous stores: rejected as the default. They amplify failures, tie teams together, and need every participant to support the protocol. Some newer databases do transactions across partitions using replicated consensus; that is a different, more contained case.
- Dual writes: rejected (ordering race, partial failure).
- Federated or polystore reads versus unbundled writes: federation unifies reads over several systems and is a manageable mapping problem. Unbundling solves the harder problem of keeping writes in sync. They are complementary.
- Log-based uniqueness versus a consensus service or a database unique index: the log gives a total order per key and scales by partitioning, at the price of asynchronous results and a client that must wait for the output. A unique index in one database is simpler and faster for modest scale. A consensus service (case 16) fits small, rare, high-value decisions, not millions of claims.
- Synchronous coordination versus loosely interpreted constraints plus compensation: coordination guarantees no violation and reduces availability. Compensation keeps the system available and moves the cost to apologies. Choose per constraint by business cost. For seats or money that must never be sold twice (case 15) prevention wins. For overbooking or stock it often does not.
- Asynchronous results versus waiting: the correctness of a uniqueness decision does not depend on the sender waiting, but the user experience does. Waiting on the output stream (with a timeout and a request ID to poll) separates timeliness from integrity.
- Microservice RPC versus subscribing to a stream: calling a rate service per request adds latency and a failure dependency. A local table maintained from the rate stream removes the network call but needs correct time-dependent replay.
- Lambda architecture versus one engine and one log: separate batch and stream code duplicates logic and merging outputs is hard. Prefer one derivation replayable through the same engine, with event-time windowing and exactly-once effects.
- Logging reads as events: gives causality, audit and consistent joins between reads and writes, and costs several times the storage of writes. Log selectively.
- Stateful push to clients versus request and response: pushes extend the write path to the screen and enable offline-first apps, but most databases and protocols assume polling and clients may be offline.
- Event sourcing as the source of truth versus a database plus CDC (case 18): the same pattern with different starting points. Event sourcing makes this design native, CDC retrofits it.

## Deep-dive topics
- Where is the boundary between write path and read path for each derived view (search, balance, notifications), and what does moving it cost in work, latency and storage?
- How exactly does the request-ID design give exactly-once effects across a retrying browser, a replaying processor and a redelivering consumer? What does TCP, a transaction and a stream processor each not cover (the end-to-end argument)?
- How do you enforce unique usernames with a partitioned log, and what changes if you repartition?
- How do you move money between accounts on different partitions without an atomic commit, and how do you add an overdraft check?
- What is the difference between timeliness and integrity, which one do you give up for scale, and how do you present results to users?
- When is a constraint better handled by compensation than by coordination, and who decides?
- How do you detect corruption (hardware, a bug in a derivation, a bug in a database) before customers do?
- How do you roll out a new derivation version without downtime, and how do you roll back?
- How do you honor a deletion request when events are immutable and replicated into many derived stores?
- When would you tell the team not to build this and use one database instead?

## Curveballs
- The validator crashes after emitting the debit instruction and before emitting the credit. Ask what the state is, what the ledger appliers see after restart, and why the money is neither lost nor doubled.
- A bug in the balance applier has been rounding currency wrongly for three days. Ask how you find the affected accounts, correct them without rewriting history, and how consumers of the numbers learn about the correction.
- The interviewer asks to double the number of username partitions. Ask what happens to uniqueness during the change, and how to migrate names without a window in which two people can claim the same name.
- Product wants the transfer confirmation screen to show the balance and fee the user saw, as evidence in a dispute. Ask what to log, what it costs, and how it links to the transfer (reads are events too).
- A regulator asks you to prove the ledger has not been altered. Ask what the log, hashes, Merkle roots and independent recomputation can and cannot show.
- A celebrity merchant receives 80% of all credits during a sale. Ask what happens to ordering, lag and the single-threaded applier, and what the remedies cost.
- The interviewer says some rejections of valid users cost real revenue: stricter than needed. Ask which constraint you would loosen and what compensation process replaces it.
- The company expands to a second region with 80 ms round trip, and the owner wants usernames unique worldwide. Ask what must be synchronous and what can stay asynchronous.
- A downstream team replays the whole request log into the search cluster by accident. Ask how the log, consumers and sinks protect each other (throttling, idempotent writes by version, offsets, quotas).

## Red flags
- Claiming the database's transactions already make the application correct end to end, with no request ID from the client.
- Using a distributed transaction or a distributed lock for every cross-system update without discussing failure modes.
- Calling a log-based design exactly-once without explaining idempotence, deduplication or atomic commit of output and offsets.
- Uniqueness enforced by "check then insert" in application code, or by asynchronous multi-master replication.
- Partitioning the username log by anything other than the username (or the key that must be unique).
- No distinction between integrity and timeliness, or treating eventual consistency as an excuse for lost or duplicated money.
- Unbounded dedup state, or retaining all request IDs forever.
- No way to rebuild or verify derived data; trusting the pipeline blindly.
- Recommending this architecture for a small system that fits in one database, with no mention of the costs.
- Ignoring privacy and deletion in an append-only world.
- Common misconception: calls log-based CDC and exactly-once stream effects emerging; CDC connectors, Kafka transactions, Flink checkpointing and managed streaming are common, and incremental table formats blur batch and stream
- Common misconception: says distributed transactions always amplify failures; true for XA across heterogeneous systems, less so for distributed SQL databases that run them inside one consensus-replicated system
- Applies the full unbundled pattern at small scale instead of saying a single technology that does everything should simply be used
- Treats blockchain or proof of work as the way to audit; a signed or hash-chained log is a modest, practical version
- Asserts integrity always matters more than timeliness; persuasive for money and records, less so for live scores
- Ignores the dedup window and request expiry, hot-account handling, repartitioning of a uniqueness log, and concrete erasure patterns

## Rubric signals
- Mid, weak: describes microservices each writing to their own stores and a queue between them, with no ordering, idempotence or replay.
- Mid, hire: explains why dual writes fail, introduces a log with consumers, derives caches and search from it, and uses a request ID for duplicate suppression.
- Mid, strong-hire: designs uniqueness by partitioning on the key, sizes partitions and dedup state, and separates the result stream from the request.
- Senior, weak: names Kafka and Flink and CQRS but cannot explain how a transfer across two partitions stays correct after a crash.
- Senior, hire: designs the two-stage transfer (single-message log write, then debit and credit instructions deduplicated by request ID), handles read-your-writes by offset, and estimates replay time bounded by sink throughput.
- Senior, strong-hire: applies the end-to-end argument, separates timeliness from integrity, argues which constraints need coordination and which can be compensated, and designs reprocessing, rollout and verification.
- Staff, weak: debates architectural styles without tying them to team boundaries, cost, operability or the business cost of violations.
- Staff, hire: treats the log as shared infrastructure with schema compatibility, ownership, retention and privacy policy, defines integrity SLOs and audits, and knows when a single database is the better answer.
- Staff, strong-hire: plans a gradual migration from the existing database to the log-centric design using CDC and dual reads, quantifies availability and failure containment against distributed transactions, designs coordination-avoiding multi-region operation, and raises the ethical and privacy duties of derived data.
