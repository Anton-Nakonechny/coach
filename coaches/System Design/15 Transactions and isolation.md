# 15 Transactions and isolation

## Opening prompt
"Design the booking core of a ticketing platform. Thousands of people try to buy seats for the same concert at the same moment. No seat may ever be sold twice, a person may hold at most four tickets per event, and nobody should pay for a seat they do not get. Talk me through the concurrency problems and how the database prevents them."

## Hidden requirements
- Q: What is the central invariant? → A: A seat is sold to at most one buyer, always. Everything else (fairness, speed, nice UX) is secondary to this. [critical]
- Q: What is the shape of the traffic? → A: A flash sale. A 50,000-seat venue goes on sale at a fixed time and several hundred thousand people arrive within minutes. Contention is concentrated on a small set of popular seats and on one event's rows. [critical]
- Q: Is there a temporary hold while the buyer pays? → A: Yes. A selected seat is held for a few minutes (say 5–10), then released if payment does not complete. Holds must expire reliably even if the buyer's browser disappears.
- Q: What other constraints exist on a booking? → A: A maximum of 4 tickets per person per event, no two overlapping bookings for the same resource in a sister product (meeting rooms or time slots), and sold-out events must stop selling. These are constraints that span several rows, and they are where weak isolation breaks. [critical]
- Q: How is payment handled? → A: By an external payment provider that can succeed, fail, time out or succeed after a timeout. The candidate should not hold a database transaction open across a payment call. [critical]
- Q: What consistency does the seat map need? → A: The displayed map can be slightly stale (a second or two), because it is advisory. The act of holding or buying must be exact.
- Q: What should happen if two buyers click the same seat at the same moment? → A: Exactly one wins, the other gets a clear "seat taken" response quickly and can choose another. No timeouts, no deadlocks that surface as errors.
- Q: Can a buyer pick specific seats or "best available"? → A: Both. Best-available makes a range query over many rows, which is exposed to phantoms and to larger lock footprints.
- Q: What is the availability expectation? → A: High during on-sale. It is acceptable to queue or throttle users (waiting room) to protect the system. It is not acceptable to oversell during failover.
- Q: Does the system span multiple databases or services? → A: Inventory, orders and payments are separate services or at least separate stores. A booking touches several. The candidate must say how the pieces stay consistent without distributed locks everywhere.
- Q: Are retries safe? → A: Clients and payment webhooks retry. Every step must be idempotent, so a repeated request neither double-charges nor double-books.
- Q: How are refunds and cancellations handled? → A: A cancelled booking returns the seat to inventory and triggers a refund. These flows can fail halfway, so they need compensation and reconciliation.
- Q: How do you prove nothing was oversold? → A: Reconciliation jobs and invariants checked independently of the booking path, plus an audit trail of every state change.

## Back-of-envelope
- Load: 500K users in the first 5 minutes, each issuing about 10 seat-map reads and 3 hold attempts. That is 5M reads (about 17K per second average over 5 minutes, bursting higher) and 1.5M hold attempts (about 5K per second average, maybe 20K per second in the first seconds).
- Contention: if the best 5,000 seats attract 50% of attempts, that is about 750K attempts on 5,000 rows, an average of about 150 attempts per hot seat, with many arriving within the same second. This is the reason row-level correctness and fast failure matter more than raw throughput.
- Successful holds are bounded by inventory: at most 50,000 seats, so at most 50,000 successful holds are live at once (plus re-holds after expiry). Most hold attempts fail by design, so the failure path must be as cheap as the success path.
- Orders: at most 50,000 purchases, and about 12,500 buyers at 4 seats each at the minimum. The payment provider, not the database, limits purchase throughput. Assume 200–500 payments per second.
- Row size: seat row about 100 bytes, 50,000 seats is 5 MB per event. The whole inventory of a hot event fits easily in one database node's memory. Sharding by event is natural and a single event does not need to be split.
- Hold expiry: with a 10-minute hold, if 30,000 holds are placed and 40% convert, about 18,000 expire per 10-minute window, about 30 per second, which a sweeper can handle trivially. Lazy expiry on read (treat expired holds as available) avoids depending on the sweeper's speed.
- Write throughput on one hot event shard: conditional single-row updates run at thousands per second per core, so a single primary can absorb 20K hold attempts per second if each attempt is one short statement and not a long transaction.
- Retry cost: serializable-isolation aborts under heavy conflict can waste work. If 50% of transactions on hot rows abort and retry up to 3 times, effective load is up to about 2× on those rows. This is another reason to prefer single-statement conditional updates for the hot path.

## Reference design
**Core API.**
- GET /events/{id}/seats returns the seat map (cacheable for about a second).
- POST /holds (event_id, seat_ids[], request_id) returns a hold with an expiry, or a conflict naming the unavailable seats.
- POST /orders (hold_id, payment_token, request_id) converts a hold into an order, or fails.
- POST /orders/{id}/cancel.
- Payment provider webhook: payment_succeeded or payment_failed (idempotent by provider event ID).

**Data model.**
- Seat: (event_id, seat_id, status in {available, held, sold}, hold_id, hold_expires_at, order_id, version). Primary key (event_id, seat_id).
- Hold: (hold_id, event_id, user_id, seat_ids, expires_at, status).
- Order: (order_id, user_id, event_id, seat_ids, state in {pending_payment, paid, failed, cancelled, refunded}, payment_ref, idempotency key with a unique constraint).
- Per-user, per-event counter row: (event_id, user_id, tickets_held_or_sold). This materializes the "at most 4" constraint into a single row that can be locked or updated atomically (the technique of materializing conflicts).
- Outbox table for events leaving the booking transaction (order paid, seat released) to be published reliably.
- Audit log of state transitions.

**Components.**
- Waiting room or admission control in front of the system to keep concurrent shoppers within what the booking core can handle.
- Stateless API servers and a read cache for the seat map.
- Inventory store: a relational database with transactions, sharded by event (a hot event lives on one primary with synchronous replicas).
- Booking service performing the hold and the order conversion as short transactions.
- Payment service wrapper handling idempotency keys, timeouts and webhook reconciliation.
- Expiry sweeper that releases expired holds, plus lazy expiry in queries.
- Reconciliation job comparing seats, orders and payments and alerting on any discrepancy.

**How each anomaly is handled (the part the interviewer is probing).**
- Lost update (two buyers read "available", both write "held"): do not read-modify-write in application code. Use one conditional statement, update the seat where status is available or the hold has expired, and check the affected row count. Exactly one request sees count 1. Alternatives that also work: SELECT FOR UPDATE on the seat rows, or automatic lost-update detection under snapshot isolation (supported by PostgreSQL repeatable read, not by plain MySQL/InnoDB repeatable read, so verify the database).
- Dirty reads and writes: read committed prevents both and is the baseline.
- Read skew in a multi-row view (a report summing seats and orders while bookings happen): snapshot isolation gives a consistent view.
- Write skew on "at most 4 tickets per person" (two concurrent requests each count 3 existing tickets and each add 1): snapshot isolation does not stop this, because the two transactions write different rows. Fix by materializing the constraint in a per-user counter row that both must update (a conflict on the same row), or by running these transactions at serializable isolation and retrying on abort, or by a unique constraint design.
- Phantoms in best-available (query a range of free seats, then insert hold rows): a lock on rows that do not exist yet is impossible with row locks, so make the seats exist as rows up front and update status (this is what the Seat table does), or use serializable isolation, or serialize best-available per event through a single writer.
- Multi-seat holds: update all requested seats in one statement or one short transaction in a consistent order (by seat_id) to avoid deadlocks, and fail the whole hold if any seat is unavailable.
- Payment: never hold the database transaction open while calling the provider. Use a state machine (hold → pending_payment → paid), idempotency keys on the charge, and reconcile on webhooks. If payment succeeds but the order commit fails, a compensating refund or a retry from the webhook completes the flow (a saga).

**Key trade-offs and rejected alternatives.**
- Pessimistic row locks (SELECT FOR UPDATE, or two-phase locking in the database): simple to reason about and correct, but readers and writers block each other, and long lock holding during a slow step is harmful. Fine for the short hold transaction, not for anything that waits on a human or a payment.
- Optimistic conflict detection (conditional updates, versions, snapshot-based abort and retry, serializable snapshot isolation): high throughput when conflicts are rare, but hot seats produce many aborts. For the hottest rows prefer a single conditional statement.
- Actual serial execution (one thread per event shard processes bookings in order): trivially correct and fast for in-memory data of this size, with the limit of a single core per event. A good fit for one 50,000-seat event and a poor fit for cross-event transactions.
- Distributed transactions (two-phase commit across inventory, orders and payments): rejected as the default because of blocking on coordinator failure and because the payment provider cannot participate. Use local transactions plus sagas and idempotency.
- Redis (or another cache) as the primary lock for holds: fast, but expiry and failover can lose or duplicate locks. Acceptable as an accelerator in front of the database, never as the authority for "this seat is sold".
- Overselling and refunding versus strict prevention: some businesses accept overbooking and compensation. For seats, the invariant is hard, so prevent.

## Deep-dive topics
- Which isolation level do you run at, and which anomalies (lost update, write skew, phantom) remain at that level for this workload?
- Why does a conditional update statement prevent the double-sell, and why does "read, check, then write" in application code not?
- How do you enforce "at most 4 tickets per person" when snapshot isolation lets two requests each see 3?
- How do you implement best-available seat selection without phantoms and without locking the whole event?
- How do you handle the payment call, given you cannot keep a database transaction open across it?
- How do holds expire reliably, and why is lazy expiry safer than relying on a sweeper alone?
- When would you pick serializable snapshot isolation, two-phase locking or single-threaded execution, and what does each cost under a hot-seat workload?
- How do you prove after the fact that no seat was oversold?

## Curveballs
- The primary database fails over during the on-sale. Ask how you guarantee no seat is sold twice if the replica was slightly behind (synchronous replication, fencing, rejecting writes during uncertainty).
- A payment is captured but the order write fails after a crash. Ask for the exact recovery sequence and which component is the source of truth.
- Reseller bots grab seats by opening thousands of sessions. Ask which parts of the design (rate limits, per-user counters, waiting room, identity) respond and where the isolation level matters.
- The interviewer moves tickets from "assigned seats" to "general admission with a capacity of 50,000". Ask how the lost-update problem changes (a single counter row), and whether sharding the counter is safe.
- Two events share a resource (the same venue or artist, with a rule of no overlapping bookings). Ask which anomaly this is (phantom or write skew) and how to prevent it.

## Red flags
- "Check availability, then insert" as two separate steps with no concurrency control.
- Holding a database transaction (or a row lock) open while waiting for the user or the payment provider.
- Claiming that the default isolation level of the database means "no concurrency problems".
- Using a cache lock as the sole source of truth.
- Distributed locks or two-phase commit proposed for everything without discussing failure modes.
- No idempotency keys on booking and payment.
- Not recognizing that a multi-row constraint (4 per person, no overlap) is a different problem from a single-row race.
- Treating serializable as free, with no talk of aborts, retries or throughput.
- Common misconception: assumes InnoDB repeatable read and PostgreSQL repeatable read behave alike for lost updates; plain read-modify-write can lose updates in InnoDB while a single `UPDATE ... SET x = x + 1` is safe, and behavior differs by version and configuration
- Common misconception: treats isolation level names as portable; Oracle's serializable is snapshot isolation and repeatable read means different things across vendors, so state the behavior needed rather than the label
- Common misconception: calls serializable snapshot isolation new or unproven, or says only DB2 and SQL Server use locks for read committed; both reflect old behavior
- Assumes one primary with single-threaded execution suffices for any hot event; general admission with a counter changes the hot-spot shape

## Rubric signals
- Mid, weak: knows transactions exist, but cannot explain what a race condition looks like or why the double-sell can happen.
- Mid, hire: demonstrates the double-sell race, fixes it with a conditional update or a row lock, adds hold expiry and a payment state machine.
- Mid, strong-hire: separates single-row races from multi-row constraints, uses idempotency keys, and avoids holding transactions across the payment call.
- Senior, weak: names isolation levels from memory but misattributes what each prevents, for example believing snapshot isolation prevents write skew.
- Senior, hire: correctly places lost update, write skew and phantoms, picks a technique for each (atomic update, materialized counter, serializable with retry), and sizes contention on hot rows.
- Senior, strong-hire: compares 2PL, serial execution and SSI for this workload with the abort and throughput consequences, designs sagas and reconciliation, and handles failover safety.
- Staff, weak: debates isolation theory without connecting it to business invariants, cost and operations.
- Staff, hire: derives the isolation and architecture choices from the invariants and traffic shape, includes a waiting room, audit trail and reconciliation, and plans for database failure during peak.
- Staff, strong-hire: anticipates organizational and vendor limits (payment provider semantics, database-specific behaviors such as lost-update detection), proposes verification (invariant monitors, chaos tests on failover), and a migration path across sharding changes.
