# 05 Unique ID generator

## Opening prompt
"We need a service that hands out unique IDs for records created by many services across several data centers."

## Hidden requirements
- Q: What format must the IDs have? → A: Numeric, fitting a signed 64-bit integer column (BIGINT).
- Q: Do IDs need to be ordered? → A: Roughly sortable by creation time (a later ID is larger, except for tiny differences between machines within a few milliseconds); strict global ordering is not required. [critical]
- Q: What scale? → A: 10K IDs/s on average today; design for 1M IDs/s at peak over three years, across about 200 generator processes. [critical]
- Q: How strict is uniqueness? → A: Duplicates must never happen, including during restarts, failovers and clock problems. [critical]
- Q: Latency? → A: In-process generation in under a microsecond; if called remotely, p99 under 5 ms.
- Q: Availability? → A: Must keep generating IDs during a network partition between data centers; no central service on the hot path. [critical]
- Q: Lifetime? → A: At least 30 years.
- Q: Are gaps allowed? → A: Yes; IDs need not be dense or consecutive.
- Q: Are IDs public? → A: They appear in URLs. Revealing creation time is acceptable; revealing order volume is not.
- Q: Geography? → A: Four data centers on three continents today, up to 32 planned.
- Q: How is it deployed? → A: A library embedded in services, with an optional standalone HTTP service for other languages; containers restart often because of autoscaling.
- Q: What about clocks? → A: Servers sync via NTP with typical skew under 50 ms; occasional backward steps happen.
- Q: Who consumes the IDs? → A: Backend services and browsers running JavaScript.
- Q: Compliance? → A: IDs must not embed personal data.
- Q: Infrastructure limits? → A: Prefer no new stateful system; the company already runs etcd.

## Back-of-envelope
- Layout used by the reference design: 1 unused bit + 41-bit timestamp (ms) + 10-bit worker id + 12-bit sequence = 64 bits.
- Timestamp lifetime: 2^41 ms = 2,199,023,255,552 ms ≈ 69.7 years from the custom epoch; 30 years needed, so there is headroom.
- Per-worker capacity: 2^12 = 4,096 IDs per millisecond = 4.096M IDs/s; with 1,024 workers about 4.2B IDs/s in theory.
- Actual load: 1M IDs/s across 200 processes = 5,000 IDs/s each = about 5 per millisecond, three orders of magnitude below the sequence limit. Even a 10x burst needs only about 50 per millisecond (6 bits). So bits can be reallocated: 6 sequence bits and 16 worker bits (65,536 workers) handle container churn better.
- Random UUID (version 4) collision odds: 122 random bits. At 1M IDs/s for 30 years, n = 1e6 x 86,400 x 365 x 30 ≈ 9.5e14 IDs. Birthday bound: n^2 / (2 x 2^122) = (9.5e14)^2 / (1.06e37) ≈ 8e-8. Collisions are not the practical problem; size and index locality are.
- Index size: a 10B-row table with 3 secondary indexes stores the primary key 4 times: 8 B vs 16 B per key is 40 B x 10B = 400 GB vs 800 GB.
- Worker id leasing: 200 processes with one restart per minute is well under 1 etcd write per second.
- JavaScript safety: numbers above 2^53 - 1 lose precision. A snowflake-style ID is the timestamp shifted left by 22 bits, so it passes 2^53 once the timestamp exceeds 2^31 ms ≈ 24.9 days after the epoch. After that, send IDs to browsers as strings.

## Reference design
**Core API:**
- In-process: `nextId() -> int64`.
- Standalone service: `GET /v1/ids?count=n` returning IDs as strings.
- Worker registry: `acquireWorkerId(processId) -> {workerId, lease}`, `renew(lease)`.

**ID layout (64 bits):** `0 | timestamp (41 bits, ms since custom epoch) | worker id (10 bits: for example 5 datacenter + 5 machine) | sequence (12 bits)`. Re-tune bits to the load as computed above.

**Generation algorithm:**
1. Read the current millisecond.
2. If it is earlier than the last timestamp used, the clock moved backward: wait out small gaps (a few ms) or stop and alert for large ones; never reuse a timestamp.
3. If it equals the last timestamp, increment the sequence; if the sequence overflows, wait for the next millisecond.
4. Otherwise reset the sequence to 0.
5. Assemble the fields into one 64-bit integer.

**Worker id assignment:** each process leases a worker id from etcd or ZooKeeper at startup, renews it, and stops generating if the lease is lost. Ids are not reused until the lease expires, so two live processes never share one.

**Components:** embedded library, worker-id registry (etcd), optional ID service behind a load balancer, monitoring of clock offset, backward-step events, sequence-overflow waits and lease losses.

**Key trade-offs and rejected alternatives:**
- Database auto-increment with step k across k servers (multi-master): rejected; IDs are not ordered by time across servers and changing k is painful.
- Random UUID (version 4): no coordination needed, but 128 bits, not numeric, not time-ordered, and random keys fragment B-tree indexes.
- Time-ordered UUID (version 7) or ULID: good choice if the 64-bit and numeric limits are relaxed.
- Ticket server (single database handing out numbers): simple and numeric but a single point of failure; handing out blocks of numbers (say 1,000,000 at a time) removes the per-ID call and avoids the bottleneck, but IDs are then ordered only within a block.
- Snowflake-style 64-bit layout (chosen): no hot-path coordination, numeric, time-sortable, high capacity. Cost: depends on clocks and needs worker-id management.
- Lamport or hybrid logical clocks: needed only if IDs must reflect causality, not just wall-clock time.

## Deep-dive topics
- What happens when the system clock jumps backward by 3 ms? By 3 seconds? What do you guarantee?
- How do you stop two processes from running with the same worker id after a restart or a network partition?
- "Sortable by time": what ordering is actually guaranteed across machines with 50 ms of clock skew?
- How do you re-split the bits if sequence is overkill but you run out of worker ids?
- If these IDs are used as a database shard key with range sharding, what happens to the newest shard?
- How do you give browsers IDs that exceed JavaScript's safe integer range?
- What breaks at leap seconds or during daylight-saving changes, and why use UTC and a monotonic clock where possible?
- How would you migrate an existing UUID-keyed system to the new IDs?

## Curveballs
- Load grows 100x. What changes in the layout, the registry and the monitoring?
- One data center's clocks drift two seconds ahead. What happens to ordering and uniqueness?
- Two data centers lose connectivity for an hour. Do IDs stay unique?
- Finance now needs gap-free, strictly increasing invoice numbers. Does this design work?
- Security asks that IDs must not reveal creation time. What do you change?

## Red flags
- Using auto-increment on one database for a multi-data-center system.
- Choosing random UUIDs while the requirement says 64-bit, numeric and time-ordered.
- Assuming clocks are perfectly synchronized and never move backward.
- Ignoring worker id assignment, so two processes can share an id.
- Claiming strict global ordering from wall-clock timestamps.
- Putting a central service on the hot path of every ID request.
- Forgetting the bit-width arithmetic (lifetime, per-millisecond capacity).
- Sending 64-bit IDs to JavaScript clients as numbers.
- Common misconception: says UUIDs never go up with time; v4 is random, but time-ordered UUID v7 (RFC 9562) exists
- Common misconception: quotes about 100 years of 1 billion UUIDs per second for a 50% collision chance; the birthday bound for 122 random bits gives about 86 years, and collisions are still negligible
- Mentions NTP but ignores a clock moving backward, which can produce duplicate IDs unless the generator refuses to issue or waits
- Common misconception: blames clock disagreement on multiple cores; the practical issue is skew and steps between machines and NTP corrections

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Picks UUID or auto-increment without testing against the requirements | Compares the four options against the constraints and lays out the 64-bit layout with bit counts | Does the lifetime and capacity arithmetic and explains why snowflake fits and the others do not |
| Senior | Assumes synchronized clocks | Handles clock regression, worker id leasing, and sequence overflow | Explains what ordering is really guaranteed, re-splits bits to the load, and covers shard-key hot spots and JavaScript precision |
| Staff | Treats it as an algorithm puzzle | Frames IDs as a long-lived contract and plans migration and monitoring | Distinguishes uniqueness, ordering and causality requirements, proposes hybrid logical clocks where needed, and plans the failure and rollout strategy |
