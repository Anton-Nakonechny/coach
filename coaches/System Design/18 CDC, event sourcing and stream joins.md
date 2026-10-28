# 18 CDC, event sourcing and stream joins

## Opening prompt
"Our orders live in a relational database. The search index, the cache and the analytics warehouse keep drifting out of sync with it, and the product team also wants near-real-time conversion numbers per product, which means joining clicks with orders. Design the data pipeline that fixes both problems."

## Hidden requirements
- Q: What is the source of truth? → A: The orders database is the only system of record. Search, cache, warehouse and metrics are derived and must be rebuildable from it. [critical]
- Q: How big is the change stream? → A: About 3K order row changes per second on average and 10K at peak (about 2 KB per change event). Separately, about 200K click events per second at about 300 bytes each.
- Q: What consistency must derived systems have with the source? → A: Eventually consistent with a bounded lag (seconds), never permanently divergent. Per-order changes must be applied in the order they happened, but no global order is needed. [critical]
- Q: Why not just write to every system from the application? → A: Dual writes race and partially fail. Two writers can apply the same two updates in opposite order in two systems, and a crash between the writes leaves them inconsistent forever. The candidate should explain this and choose a single ordered log as the mechanism. [critical]
- Q: How do we capture changes from the database? → A: Read the database's own replication log (change data capture) or use a transactional outbox table. Polling with a timestamp column misses deletes and in-flight transactions, so it is a weaker option.
- Q: How does a new consumer get started? → A: It needs a consistent snapshot of the current state plus all changes after the snapshot point, with no gap and no duplicate application. Alternatively a compacted log that holds the latest value per key.
- Q: How are deletes represented? → A: As an explicit delete event (a tombstone) for the key, so every consumer can remove the record, and log compaction can eventually drop it.
- Q: What delivery semantics are required? → A: The pipeline is at-least-once, and consumers must be idempotent (or apply updates by key and version), so the effect is exactly once. A duplicate event must not double-count a metric. [critical]
- Q: What does the click and order join compute? → A: Per product and per 5-minute window, clicks, add-to-carts and orders so conversion can be shown with under a minute of delay. Events arrive late and out of order.
- Q: How is time defined for the windows? → A: By event time (when the click happened on the device), not by arrival time, with a bounded lateness allowance and a rule for very late data (drop, side output, or correct later through batch).
- Q: What happens when a consumer crashes or falls behind? → A: It resumes from its last committed position in the log, and lag is monitored and alerted on. A slow consumer must not slow others.
- Q: How are schema changes handled? → A: Events carry a versioned schema with compatibility rules (add optional fields, never reuse ids). Producers and consumers upgrade independently.
- Q: Is event sourcing required for orders? → A: Not required, but the candidate should explain the choice: events as the source of truth (state derived from an append-only log) versus state in a database with a change log derived from it, and when each is appropriate.
- Q: Is there any privacy or deletion requirement? → A: Yes. A user's personal data must be erasable. An immutable log makes this hard, so the candidate should mention strategies (avoid personal data in events, encrypt per user and delete keys, or compact and rewrite).

## Back-of-envelope
- Change events: 3K per second average and 10K at peak × 2 KB is 6 MB per second average and 20 MB per second at peak. Per day about 520 GB at the average rate.
- Click events: 200K per second × 300 B is 60 MB per second, about 5 TB per day.
- Log retention: orders at 6 MB/s for 7 days is about 3.6 TB, ×3 replication about 11 TB. Clicks at 60 MB/s for 3 days is about 15.5 TB, ×3 about 47 TB. Retention is a cost lever, and anything older lives in object storage or the warehouse. A compacted topic for current order state is bounded by the number of live orders (for example 500M orders × 1 KB is about 500 GB).
- Partitions: a single partition handles on the order of 10–50 MB per second. For 20 MB per second peak of orders, 8–16 partitions would do, but 64 gives headroom and parallelism for consumers. For 60 MB per second of clicks, 64–128 partitions. Partition count caps consumer parallelism and is hard to change later, so pick with growth in mind. Keys: order id for order events (keeps each order's changes in order on one partition), product id or session id for clicks depending on the join key.
- Consumer lag budget: a lag target of 5 seconds at 20 MB per second peak means a backlog of at most about 100 MB, which a healthy consumer clears quickly. After a 10-minute outage the backlog is 12 GB, and a consumer that is 3× faster than the input needs about 5 minutes to catch up.
- Join state: a stream-to-stream join of clicks with orders over a 30-minute window keyed by user and product. If only clicks that could match are retained: 200K per second × 1,800 seconds = 360M events × 300 B is about 108 GB of state. Spread over 50 tasks that is about 2 GB per task, which fits an embedded state store (for example RocksDB) with changelog backup. If the window were 24 hours, state would be about 5 TB, so the window is a major cost driver.
- Enrichment join (stream-table): joining each click with product attributes means a lookup per event. 200K lookups per second cannot go to a remote database. A local copy of the product table maintained from its own change stream, about 500M products × 200 B is about 100 GB, partitioned by product across tasks, is the usual answer.
- Aggregates: per product and per 5-minute window, 50M products × 288 windows per day, but only the active ones have rows. At 20M active products × 16 B × 288 is about 92 GB per day of metrics if all windows are kept, so keep only recent windows in the serving store.
- Exactly-once cost: transactional writes to the log and state stores add latency (commit interval of 100 ms to a few seconds) and some throughput overhead (often 10–30%). The effect is justified for money-related counts, and probably not for approximate dashboards.

## Reference design
**Core API.**
- No public API for the capture path. Contracts instead: topic names, key choice, event schema (with version), delivery semantics, retention and compaction settings.
- Event shape: entity id, entity type, operation (insert, update, delete), before and after images or the new state, source log position, source commit timestamp, schema version.
- Metrics read API: GET /products/{id}/conversion?window=5m returns counts and rate, served from a low-latency store.

**Data model.**
- Source: orders tables in the relational database, with its write-ahead or replication log.
- Change topic keyed by order id, partitioned, with the source log position carried in each event, retained for days. A compacted topic keeps the latest state per order id.
- Click topic keyed by product id (or session id), retained for days.
- Derived stores: search index documents keyed by order or product id, cache entries keyed by id with a version, warehouse tables loaded in micro-batches, metrics store keyed by (product, window start).
- Processor state: keyed state in a local embedded store with periodic checkpoints to durable storage and a changelog topic.
- Dead-letter topic for events that fail validation or processing.

**Components.**
- CDC connector reads the database's replication log from a stored position, publishes ordered change events, and handles initial snapshots and schema changes. The alternative is a transactional outbox: the application writes the event to an outbox table in the same transaction as the order, and a relay publishes from there.
- Partitioned, replicated log (Kafka-style) as the backbone: durable, ordered per partition, replayable, consumers track offsets.
- Sink consumers: search indexer, cache updater, warehouse loader. Each applies events by key, using the entity version or source position to ignore stale or duplicate events.
- Stream processor (Flink, Kafka Streams, Spark Structured Streaming): reads clicks and orders, assigns event time, uses watermarks, joins and aggregates in windows, and writes results with idempotent or transactional sinks.
- Reference data: product table loaded as a stream-table join, with its own change topic kept in local state.
- Backfill and reprocessing: replay the log (or snapshot plus log) into a new consumer or a new version of the job, then switch over. This is how bugs are fixed after the fact.
- Monitoring: end-to-end lag per consumer, event-time skew, state size, checkpoint duration, dead-letter volume, reconciliation of counts between source and derived systems.

**Key trade-offs and rejected alternatives.**
- Dual writes from the application: rejected, because of the ordering race and the partial failure window.
- Timestamp polling of the source tables: rejected as the main mechanism, because it misses deletes and can skip rows committed out of order, and loads the database with queries. Acceptable for a small, low-stakes sync.
- CDC from the replication log versus an outbox table: CDC needs no application changes and captures everything including changes from other writers, but exposes the table schema as a public contract. An outbox publishes deliberate domain events and decouples from table layout, at the cost of application code and an extra table.
- Event sourcing (events as the system of record) versus CDC over a normal database: event sourcing keeps a full history and makes derived views trivially rebuildable, but needs discipline about event design, snapshots, versioning and querying current state, and handling erasure. CDC retrofits a log onto an existing system with less change.
- Log compaction (latest value per key) versus time-based retention: compaction lets a new consumer bootstrap from the log alone and bounds storage by the number of keys, but loses history. Use both on different topics.
- At-least-once with idempotent consumers versus transactional exactly-once: idempotency by key and version is cheaper and works across heterogeneous sinks. Transactions work inside the log and processor and not across arbitrary external stores.
- Windowing by event time versus processing time: processing time is simple but gives wrong answers when events are late or replayed. Event time with watermarks is correct but needs an explicit lateness policy.
- Stream-stream join (buffer both sides in a window), stream-table join (look up a changelog-fed table), and table-table join (maintain a materialized view of both): choose based on whether time matters and which side changes.

## Deep-dive topics
- Why do dual writes produce permanent inconsistency, and how does a single ordered log fix both the ordering and the partial-failure problems?
- How do you take a consistent snapshot of the source and splice it with the change stream with no gap and no duplicates?
- How do consumers stay correct when events are duplicated or redelivered after a crash (idempotence, versions, transactional offsets)?
- What is the right key and partitioning for the order topic and for the click topic, and what constrains the partition count?
- How do event time, processing time, watermarks and late events interact in the conversion windows?
- What do the three kinds of stream joins need to remember, and how much state does each one cost?
- How do you reprocess history after a bug in the job without disturbing live results?
- How do you honor a user's erasure request when the history lives in an immutable log?

## Curveballs
- The CDC connector is down for two hours and its stored position falls off the source database's log retention. Ask how you recover without a gap (resnapshot, reconcile, alarm before retention is exceeded).
- A bug in the join logic has produced wrong conversion numbers for three days. Ask how you correct them, and how consumers of the numbers learn about the change.
- A consumer deploy replays the topic from the start by accident and floods the search cluster. Ask what protects downstream systems and how updates stay idempotent under replay.
- The product team asks for exactly-once accuracy of revenue metrics across the stream processor, a key-value store and a warehouse. Ask what is actually achievable and what the end-to-end argument says about it.
- Late data: a mobile app uploads a day of buffered clicks after a flight. Ask how windows, watermarks and corrections handle this and what the dashboard shows meanwhile.

## Red flags
- Updating the database, then the search index and cache from the same request path, and calling the sync "eventual".
- Polling by "updated_at" with no mention of deletes or transaction ordering.
- No ordering story: events for one entity spread across partitions.
- Claiming exactly-once delivery from the network without idempotence or transactional mechanisms.
- Aggregating by arrival time and presenting it as event-time truth.
- Remote database lookup per event at hundreds of thousands per second.
- No way to rebuild a derived system from scratch.
- An unbounded join window and no estimate of state size.
- Common misconception: says Kafka lacks exactly-once and that Spark only microbatches; Kafka has idempotent producers and transactions, Flink popularized checkpoint-based exactly-once state, and the key idea is atomic commit of output and offsets
- Common misconception: treats log-based CDC as immature; connectors are mature and managed offerings exist
- Proposes keeping all events forever for event sourcing without addressing privacy and erasure
- Common misconception: timestamp polling misses deletes; true for hard deletes, weaker with soft deletes, but rows committed out of timestamp order can still be missed

## Rubric signals
- Mid, weak: proposes writing to the database and then to the search index in the same code path, with retries.
- Mid, hire: introduces a log or queue between the database and consumers, explains why it avoids dual-write problems, and keys events for per-entity ordering.
- Mid, strong-hire: uses CDC or outbox, handles snapshot plus stream bootstrap, makes consumers idempotent, and sizes the topic and partitions.
- Senior, weak: can name Kafka and Flink but cannot explain how duplicates, lateness or restarts affect results.
- Senior, hire: distinguishes event time from processing time, windows and watermarks, describes join types with state size estimates, and plans lag monitoring.
- Senior, strong-hire: designs replay and reprocessing as normal operations, evaluates outbox versus CDC and event sourcing versus derived logs, and reasons about exactly-once across system boundaries.
- Staff, weak: builds a pipeline for the one use case and ignores governance, schema ownership and cost.
- Staff, hire: treats the log as a shared platform, with schema compatibility, ownership, retention and privacy policies, and measures end-to-end freshness.
- Staff, strong-hire: applies the end-to-end argument and constraint enforcement ideas (uniqueness and integrity via the log) to keep derived data trustworthy, plans verification (continuous reconciliation, audits), and decides where strong consistency is needed versus eventual.
