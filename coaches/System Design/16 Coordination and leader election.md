# 16 Coordination and leader election

## Opening prompt
"Design a coordination service for a fleet of application servers, something in the spirit of ZooKeeper or etcd. Services need to elect a leader, take exclusive locks, discover each other and share small pieces of configuration. Tell me what guarantees you provide and what happens when nodes crash, pause or get partitioned."

## Hidden requirements
- Q: What must the service guarantee about a lock or a leader? → A: At most one holder at a time as seen by the protected resource, not merely as believed by the clients. The candidate must separate "the service granted it" from "the holder is still entitled to act". [critical]
- Q: What scale? → A: Tens of thousands of client sessions, about 1M small keys, a few thousand lock and config writes per second, and hundreds of thousands of reads and watches. It is a low-volume, high-importance service. Data is small (gigabytes at most) and fits in memory.
- Q: What consistency do reads need? → A: Writes are linearizable. Reads can be linearizable on request, but the default may be served by followers and be slightly stale if documented. A client must be able to ask for the strongest read. [critical]
- Q: How do clients detect a dead holder? → A: Sessions with heartbeats and timeouts, or leases that expire. A paused or partitioned holder will look dead and come back later, still believing it holds the lock. This is the core hazard. [critical]
- Q: How many nodes and what failures are tolerated? → A: 3 or 5 voting members in separate failure domains. 5 tolerates 2 failures. The candidate must say why an even number is no better and why 7 or more is rarely worth it.
- Q: What happens during a network partition? → A: The side with a majority continues, the minority side stops accepting writes (and linearizable reads). The service is unavailable on the minority side by design: CP in CAP terms, or in PACELC terms it pays latency for consistency even without partitions.
- Q: Can the service rely on synchronized clocks? → A: No for correctness. Timeouts are used for liveness only, and safety must hold even if clocks drift or processes pause.
- Q: How do clients learn about changes? → A: Watches or subscriptions that notify clients when a key or lock changes. Notifications may be lost on reconnect, so clients must re-read state. Delivery is ordered per client.
- Q: What are the latency targets? → A: Write commit in single-digit milliseconds inside one region, with a leader election after failure completing within a few seconds. Cross-region voting members are discouraged because every write pays the inter-region round trip.
- Q: How are members added or replaced? → A: Membership changes are themselves agreed by consensus, one change at a time or through joint configurations. A replaced node must catch up from a snapshot and the log.
- Q: What is stored on disk? → A: A write-ahead log with fsync before acknowledgement, plus periodic snapshots. Without durable logging, a restarted majority could forget committed decisions.
- Q: What do you do about clients that hold a lock while talking to some other storage service? → A: Fencing tokens: a monotonically increasing number issued with every grant, which the downstream resource checks and uses to reject stale holders. This must be explicit in the design. [critical]
- Q: Is Byzantine fault tolerance needed? → A: No. Nodes may crash or be slow, but they are assumed not to lie. The candidate should state this assumption.

## Back-of-envelope
- Cluster: 5 voting members, quorum of 3. Tolerates 2 simultaneous failures. A 3-node cluster tolerates 1 and is cheaper, and is acceptable if each node is quickly replaceable.
- Sessions: 50K clients, heartbeat every 3 seconds, session timeout 10 seconds (about 3 missed beats). That is about 17K heartbeats per second spread across members. Heartbeats are handled by the member the client is connected to. Only session creation and expiry go through the replicated log.
- Writes: 5K per second of lock acquire, release and config updates. A leader that batches log entries and fsyncs once per batch handles this easily, typical limits are in the range of 10K–50K small writes per second per cluster on SSDs. Throughput does not scale by adding members, adding members makes writes slower.
- Commit latency: one fsync on SSD (about 0.1–1 ms) plus one network round trip to a majority (about 0.5 ms within a zone, 1–2 ms across zones in a region) gives about 2–5 ms for a write. If the voters were spread across regions at 60–100 ms round trips, every write would cost that, which is why the coordination cluster stays in one region with members in 3 zones.
- Reads and watches: 500K reads per second served by followers is easy because they come from memory, but they are not linearizable unless forwarded to the leader or confirmed. 1M watches at about 200 bytes of server memory each is about 200 MB.
- Memory and disk: 1M keys × 1 KB average is about 1 GB in memory, plus a few GB of log and snapshots. If this grows past tens of GB the service is being misused as a database.
- Election time: failure detection (timeout of 1–3 seconds) plus a leader election round (tens to hundreds of milliseconds) gives roughly 2–5 seconds of write unavailability after a leader crash. Shorter timeouts risk repeated elections on a slow network. Raft's randomized election timeouts reduce split votes.
- Lock lease: if a lock lease is 15 seconds and a client's garbage collection pause is 20 seconds, the client wakes up believing it still holds the lock after it has expired and been granted to someone else. The pause needs only exceed the lease once to cause a violation. Fencing tokens close that window.
- Token size: a 64-bit counter lasts effectively forever at 5K grants per second (more than 100 million years).

## Reference design
**Core API.**
- create_session(timeout), heartbeat(session), close_session.
- create(key, value, ephemeral=true or false, sequential=true or false), get(key, watch), set(key, value, expected_version), delete(key, expected_version), list_children(prefix, watch).
- acquire(lock, session) returns a fencing token (the lock node's version or sequence), release(lock).
- All writes conditional on version, so clients can build compare-and-set and lost-update-safe patterns.

**Data model.**
- A small hierarchical key space or flat key space with prefixes, each key with value, version, create and modify indexes (monotonic log positions), and an optional owning session.
- Session table: session id, timeout, last heartbeat (tracked by the leader, not through the log on each beat), owned ephemeral keys.
- Lock as a key: acquiring creates a sequential ephemeral entry under the lock path. The lowest sequence number holds the lock, each waiter watches only its predecessor to avoid a thundering herd. The sequence number or the log index at grant time serves as the fencing token.
- Replicated log of commands, snapshots of the state machine at a log index.

**Components.**
- Consensus module (Raft, Zab or Paxos family): a single leader orders all writes, replicates to followers, commits when a majority has persisted them. Every leader has a term (epoch) number, and a node with a stale term is rejected. This prevents two leaders from both committing.
- State machine applying committed commands in log order, which is what makes all members converge.
- Client library: session management, reconnect to another member, re-establish watches, handle session expiry as a hard event (stop acting as leader or lock holder immediately).
- Fencing enforcement lives in the protected resource (database, storage service). It remembers the highest token seen and rejects lower ones.
- Monitoring: leader changes, commit latency, fsync latency, log size, number of watches, session expiries.

**Key trade-offs and rejected alternatives.**
- Single database with a lock row: simple and acceptable for low stakes, but the database becomes the single point of failure, and a replicated database brings the same failover questions. A coordination service does it once and properly.
- Lock via a lease and clock in Redis or similar (single node or async replicas): fast, but failover can lose the lock and a paused client can still act. Suitable for efficiency locks (avoid duplicate work), not for correctness locks.
- Consensus (Raft, Paxos, Zab) versus gossip and eventual consistency: gossip is available and scales to thousands of nodes but cannot give a single leader or exclusive lock. Rejected for this use.
- Odd cluster sizes: 4 nodes need 3 for a quorum and tolerate the same single failure as 3 nodes, so the extra node adds cost and write latency for no gain.
- Lease timeouts versus fencing tokens: timeouts decide when to give up on a holder (liveness), fencing decides who is allowed to act (safety). Lease duration alone cannot give safety, because pauses and delays are unbounded in practice.
- Linearizable reads through the leader (or with a read index or lease) versus fast follower reads: choose per call, and document the staleness of the default.
- Do not put the application's data path through the coordinator. It stores small, rarely changing, high-value facts.

## Deep-dive topics
- How does a paused or partitioned lock holder cause a violation, and how do fencing tokens prevent it?
- Why does consensus need a majority and how do epoch or term numbers prevent two leaders from deciding conflicting things?
- What exactly happens during a leader election in your protocol, and how long are writes unavailable?
- How do you implement a lock with ephemeral sequential keys, and why should each waiter watch only its predecessor?
- How do linearizable reads differ from follower reads, and when is each acceptable?
- What do you persist and when do you fsync, and what breaks if you acknowledge before the log is durable?
- How are cluster membership changes made safely?
- How do clients behave when their session expires or when they lose contact with the cluster?

## Curveballs
- The leader of the service is partitioned from the rest but still thinks it is the leader. Ask what it does with incoming writes and what the other side does.
- A client holds a lock, then a 40-second stop-the-world GC pause happens, then it writes to storage. Ask for the sequence of events and where the protection is.
- The interviewer asks to stretch the cluster across two regions with 80 ms round trip. Ask about write latency, availability when one region fails, and whether 2 regions can ever be safe (they cannot without a third voter or witness).
- 100,000 clients all watch the same key and it changes. Ask how to avoid the thundering herd on the service and on the clients.
- A flapping network makes leadership bounce repeatedly. Ask what mitigations exist (pre-vote, randomized timeouts, leader stickiness, tuning timeouts to the network).

## Red flags
- Trusting a lease or timeout alone for mutual exclusion.
- Using wall-clock timestamps as the way to decide who holds the lock.
- A "distributed lock" built on a single Redis instance or a replicated cache described as safe for correctness.
- Even-sized clusters, or "more nodes means more throughput".
- No mention of what happens to the minority side in a partition.
- Putting large or frequently changing application data in the coordinator.
- Forgetting that a session expiry must make the client stop acting immediately.
- Treating consensus as a black box with no understanding of epochs and majority overlap.
- Common misconception: names ZooKeeper or etcd as the only coordination options; several systems now embed consensus (for example Kafka's Raft-based controller quorum)
- Common misconception: says leadership bouncing under flaky networks is unsolved in Raft; mitigations such as pre-vote make it much less severe in mature implementations
- Does not distinguish a lock held for efficiency from one held for correctness, and ignores read paths (read index, read leases)

## Rubric signals
- Mid, weak: proposes a lock row or a Redis key with a TTL and does not see how the lock can be held by two clients.
- Mid, hire: explains leases, heartbeats and expiry, picks an odd cluster with a majority quorum, and uses a replicated store with a leader.
- Mid, strong-hire: introduces fencing tokens unprompted, explains why TTL alone is unsafe, and designs lock queues with watches on the predecessor.
- Senior, weak: names Raft or Paxos but cannot explain what quorum intersection or terms protect against.
- Senior, hire: explains epoch numbers, linearizable writes versus configurable reads, durable log with fsync, and failure behavior under partition.
- Senior, strong-hire: ties each guarantee to a failure scenario (pause, partition, clock skew), designs client behavior on session loss, and sizes the cluster and write latency including the cost of placing voters across zones.
- Staff, weak: debates protocols without connecting them to which applications really need strong guarantees.
- Staff, hire: separates correctness locks from efficiency locks, advises when not to use the coordinator, and plans operations (upgrade, membership change, disaster recovery).
- Staff, strong-hire: reasons about blast radius (everything depends on the coordinator), multi-region strategies (witness, hierarchical coordination) and the organizational cost of a shared critical service.
