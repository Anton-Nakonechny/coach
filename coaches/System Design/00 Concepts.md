# 00 Concepts

Purpose: the shared vocabulary behind design cases 01-19. Each entry has a short definition, what a strong candidate says or does with it, and the cases where it appears. "Case n" means a design-case file.

## A. Describing load and performance
**Load parameters.** The few numbers that describe a system's load (requests per second, read/write ratio, fan-out, active users, data size). Pick them first, then derive the design. The average hides the tail: Twitter's 75 followers on average versus 30M at the top. Say which parameter drives the design. Cases 14, 19.

**Percentiles and tail latency.** Report p50, p95, p99, p999, not the mean. A request that touches many backends is slow if any one is slow: 1 − (1 − p)^n. Set a per-backend p99 budget. Cases 14, 17.

**Throughput versus response time.** Batch systems care about throughput, online systems about response time. Cases 17, 14.

**Fan-out.** The number of downstream writes or reads caused by one operation. Fan-out on write precomputes results at write time and multiplies writes. Fan-out on read computes at read time and multiplies reads. Hybrid picks per entity (celebrities on read). Cases 14, 19.

**Write path and read path.** The write path is the work done eagerly when data arrives, the read path the work done only when someone asks. A derived dataset (index, cache, materialized view) is where they meet. Moving the boundary trades write work against read work, as with Twitter's celebrity rule. Cases 14, 19.

**Back-of-the-envelope estimation.** Round numbers, write the assumptions and units, state which decision each number changes. See 00 Numbers cheat sheet.

**Hot key / skew.** A few keys receive a disproportionate share of traffic or data. It causes stragglers, out-of-memory failures, hot partitions. Remedies: salting and replicating the other side of a join, splitting hot keys, sub-accounts for counters, isolating heavy accounts. Cases 14, 15, 17, 19. .

## B. Replication, partitioning, consensus
**Replication log.** The ordered record of changes from which followers (or derived systems) are kept in sync. Cases 18, 19.

**Replication lag, read-your-writes, monotonic reads.** Followers lag; a user must see their own write and must not see time go backwards. Implement with a position token (offset) or sticky routing. Cases 14, 19.

**Partitioning (sharding) and key choice.** Split data by key so partitions are processed independently. The key decides ordering scope, hot spots and which operations can be local. Secondary indexes cross partitions (document-partitioned or term-partitioned). Cases 14, 15, 18, 19.

**Linearizability.** The system behaves as if there were one copy and every operation takes effect atomically at one point in time. Expensive, needed for locks, leader election, uniqueness. Cases 15, 16, 19.

**Total order broadcast / consensus.** Deciding one order of events for all nodes; equivalent to consensus. One leader orders everything, so throughput is bounded by one node unless you partition. Cases 16, 19.

**Quorum and majority intersection.** Any two majorities overlap, so a decision made by a majority cannot be contradicted by another majority. Odd cluster sizes. Case 16. 364–373.

**Epoch / term / ballot number.** A monotonically increasing number identifying a leader's reign. A node with a stale epoch is rejected, which prevents two leaders from both committing. Case 16.

**Fencing token.** A monotonically increasing number issued with each lock grant. The protected resource rejects any request with a lower token than it has seen. It makes mutual exclusion safe against pauses and delays. Timeouts give liveness, fencing gives safety. Case 16.

**Lease, heartbeat, session.** A time-bound claim that must be renewed. A paused or partitioned holder looks dead and may come back believing it still holds the claim. Case 16.

**Process pause and unreliable clocks.** Garbage collection, VM stalls and clock drift mean timeouts cannot give safety on their own. Case 16.

**CAP and PACELC (practical view).** Under a partition choose between consistency and availability. Without partitions you still choose between latency and consistency. A majority-quorum system is CP: the minority side stops. Case 16. (the cost of linearizability).

**Thundering herd.** Many waiters or clients react to one event at once. Mitigate: each lock waiter watches only its predecessor, jittered backoff, stampede protection on cache rebuild, rate limits. Cases 14, 16.

## C. Transactions and isolation
**ACID.** Atomicity (all or nothing), consistency (application-level invariants, which the application must maintain), isolation, durability. Vendors use the words inconsistently. State the behavior you need, not the label. Case 15.

**Isolation levels and the anomalies they leave.** Read committed prevents dirty reads and writes. Snapshot isolation gives a stable view and prevents read skew but does not prevent write skew. Serializable prevents all of them at a cost. Case 15.

**Lost update.** Two read-modify-write cycles overwrite each other. Fix with an atomic update or conditional write, explicit locks, compare-and-set, or the database's detection where it exists. Case 15.

**Write skew and phantoms.** Two transactions read overlapping data and write different rows, breaking a multi-row rule (at most 4 tickets, no overlap). Fix by materializing the conflict in a single row, locking, or serializable isolation. A phantom is a write that changes the result of an earlier range query. Case 15.

**Materializing conflicts.** Turn a multi-row constraint into a conflict on one concrete row (a per-user counter row) so ordinary locking applies. Case 15.

**Two-phase locking, serial execution, serializable snapshot isolation.** Three ways to get serializability: pessimistic locks (blocking), one thread per partition (fast for in-memory data, one core), optimistic detection (aborts under contention). Case 15. 257, 261.

**Optimistic versus pessimistic concurrency.** Optimistic assumes conflicts are rare and aborts and retries; pessimistic locks first. Hot rows favor a single conditional statement. Case 15.

**Idempotence and idempotency key.** An operation that has the same effect if executed once or many times. Needed wherever a retry can happen (client, webhook, redelivery). Carry a client-generated request ID to the final write. Cases 15, 18, 19.

**Saga and compensation.** A long flow made of local transactions, each with a compensating action (refund, release), with idempotent steps and reconciliation. The alternative to distributed transactions across services and external providers. Case 15. (compensating transactions).

**Two-phase commit and distributed transactions.** Atomic commit across systems through a coordinator. It blocks if the coordinator fails, needs every participant to support it, and spreads failures. Case 15, 19.

**Transactional outbox.** Write the event to an outbox table in the same transaction as the state change and relay it to the log. Avoids dual writes. Cases 15, 18.

## D. Batch and stream processing
**Immutable inputs, versioned outputs.** Jobs read immutable data and write new versioned outputs, so reruns, rollbacks and debugging are safe. Case 17.

**Determinism.** Same inputs and code give the same outputs: no wall-clock reads, fixed seeds, logical run date as a parameter. Required for safe retry and replay. Cases 17, 19.

**Reduce-side join, broadcast (map-side) join, bucketed join.** Shuffle both inputs by key; ship a small side to every worker; pre-partition inputs on the join key. Pick by size and skew. Case 17.

**Atomic publish and rollback.** Build the output offline, check it, then flip a pointer (alias or version). Rollback is another flip. Never overwrite production in place. Case 17.

**Quality gate.** Row counts versus yesterday, null rates, join match rates, known-query comparisons; failing stops the publish. Case 17.

**Log-based message broker.** An append-only, partitioned, replicated log with consumer offsets. Ordering is per partition. Consumers can replay and fall behind without hurting others. Cases 18, 19.

**Log compaction.** Keep the latest value per key (with tombstones for deletes) so a new consumer can bootstrap from the log alone. Cases 18, 19.

**Change data capture (CDC).** Read the database's own replication log and publish ordered change events, with an initial consistent snapshot spliced to the stream. Cases 18, 19.

**Event sourcing.** Store immutable events as the source of truth and derive state from them. Compare with CDC, which retrofits a log onto a database. Cases 18, 19.

**Dual write.** Writing to two systems from application code. Concurrent writers can apply updates in opposite orders and a crash between writes leaves permanent inconsistency. Replace with one log. Cases 18, 19.

**Event time, processing time, watermark, lateness.** Windows are defined on when the event happened, with a bound on how late data may be, and a rule for very late data. Case 18.

**Stream joins.** Stream-stream (buffer both sides in a window), stream-table (enrichment from a changelog-fed local table), table-table (materialized views). State size is the cost driver. Time dependence: a replay must join with the value valid at the event's time. Cases 18, 19.

**Exactly-once effect.** At-least-once delivery plus idempotent processing, or atomic commit of output and offsets inside one system. It does not extend across arbitrary external stores without an end-to-end ID. Cases 18, 19.

**Checkpointing and local state.** Processors keep keyed state in an embedded store, with periodic checkpoints and a changelog for rebuilding. Case 18.

**Lambda architecture and unified batch and stream.** Lambda runs batch and stream implementations in parallel and merges them, with double maintenance. The alternative is one engine and one log with replay, event-time windows and exactly-once effects. Cases 17, 19.

**Reprocessing for evolution.** Derive a new view from the log next to the old one, shift traffic gradually, keep the old one for rollback (the railway dual-gauge analogy). Cases 17, 19.

**Dead-letter topic.** Where events that fail validation or processing go, so they do not block the stream and can be inspected. Case 18.

## E. Derived data and unbundling
**System of record versus derived data.** One system holds the authoritative facts; indexes, caches, views, models and warehouse tables are derived and rebuildable. Cases 17, 18, 19.

**Unbundling the database.** Treat index maintenance, materialized views and replication as separate components connected by a log, instead of features inside one product. It gives breadth (many access patterns), not depth (performance of a single tuned engine). If one product does everything you need, use it. Case 19.

**Federated (polystore) versus unbundled.** Federation unifies reads across systems; unbundling keeps writes synchronized across them. Case 19.

**Application code as derivation function.** A derived dataset is the result of a (possibly custom) transformation: a secondary index, a full-text analysis chain, a model, a UI cache. Case 19.

**Materialized view and cache.** A precomputed query result. A cache is the same idea with weaker guarantees. Both move the boundary toward the write path. Cases 14, 19.

**Stateful and offline-capable clients; pushing changes.** Device state is a cache of server state. Extend the write path to the screen with a subscription (server-sent events, WebSockets) and resume by offset after reconnect. Case 19.

**Reads are events too.** Route reads through the same log (a stream-table join) to capture causality and provenance, at the price of storage and I/O. Case 19.

## F. Correctness, constraints, trust
**End-to-end argument.** A function such as duplicate suppression or integrity checking can only be done completely at the endpoints of a communication. TCP, TLS, transactions and stream processors reduce the problem but cannot remove it. A request ID from client to final write closes the gap. Case 19.

**Timeliness versus integrity.** Timeliness: observing an up-to-date state; violations are temporary ("eventual consistency"). Integrity: no loss, no corruption, no contradiction; violations are permanent until repaired ("perpetual inconsistency"). In most applications integrity matters more. Dataflow systems decouple the two. Case 19.

**Uniqueness constraint needs a total order.** Route all conflicting requests to one partition, process them in order, emit accepted or rejected. Scale by partitioning on the unique value. Asynchronous multi-master cannot do it. Cases 16, 19.

**Multi-partition operations without atomic commit.** Log the request as one message, derive per-partition instructions deterministically, deduplicate by request ID. Case 19.

**Loosely interpreted constraints.** Many business rules can be violated briefly and repaired by an apology or compensation (overbooking, stock, overdraft limits). Whether that is acceptable is a business decision about the cost of the apology. Case 19.

**Coordination-avoiding systems.** Integrity without linearizability or cross-partition atomic commit; weak timeliness; can run multi-leader across regions. Case 19.

**Trust, but verify; auditing.** Do not assume hardware, software or the database is always right. Continuously recompute and compare, use hashes over logs, make derivation deterministic so it can be re-run. Cases 18, 19.

**Reconciliation and invariant monitors.** Independent jobs that check that the books balance (total debits equal total credits), counts match between source and derived systems, nothing was oversold. Cases 15, 18, 19. (see; the specific checks are mine)

**Erasure and retention in an immutable log.** Keep personal data out of events, or encrypt per user and delete the key, or compact and rewrite; purge data when it is no longer needed. Cases 18, 19.

**Ethics of data systems.** Engineers are responsible for how derived data is used: predictive decisions about people, feedback loops, surveillance, consent. A short, honest remark about it is a staff-level signal. Case 19.

## G. Design-process vocabulary
**Four steps.** (1) Understand the problem and establish scope by asking questions; (2) propose a high-level design and get buy-in, with back-of-the-envelope checks; (3) design deep dive into the components the interviewer cares about; (4) wrap up: bottlenecks, failures, operations, next scaling step.

**Over-engineering.** Adding complexity (here: unbundling, event sourcing, multi-region) without a requirement that justifies it. A red flag in the interview.

**Percentile-based SLO, error budget, delivery lag.** Define the objective on a percentile and on freshness, and measure it. Cases 14, 18.
