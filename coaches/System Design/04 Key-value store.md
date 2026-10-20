# 04 Key-value store

## Opening prompt
"Design a distributed key-value store that other teams in the company can use for their service data. It needs put and get."

## Hidden requirements
- Q: Which operations? → A: `get`, `put` and `delete`, with an optional per-key time-to-live (TTL). No range scans, no secondary indexes, no multi-key transactions.
- Q: How large are values? → A: Up to 10 KB, median 1 KB.
- Q: What scale? → A: 10 TB at launch growing to 50 TB (about 50B keys) within two years, before replication; 500K operations/s at peak. [critical]
- Q: Read/write mix? → A: 80% reads, 20% writes.
- Q: Latency target? → A: In-region p99 under 10 ms for `get` and under 20 ms for `put`.
- Q: Consistency? → A: Eventual consistency by default; some tenants need read-your-writes on a key; no strong consistency across regions. [critical]
- Q: Availability? → A: 99.99% for reads and writes; accepting a write during a failure is preferred over blocking it. [critical]
- Q: Durability? → A: An acknowledged write must survive the loss of any one node or availability zone. [critical]
- Q: Conflicts? → A: Last-writer-wins is acceptable for most tenants; one tenant (a shopping cart) needs concurrent writes merged.
- Q: Geography? → A: One region with three availability zones; an asynchronous second-region copy for disaster recovery.
- Q: Multi-tenancy? → A: About 30 internal teams; per-tenant quotas and no noisy neighbors.
- Q: Operations? → A: Add and remove nodes without downtime, automatically; a five-person team runs it.
- Q: Security and compliance? → A: Encryption in transit and at rest; a tenant's data must be hard-deletable within 30 days (GDPR).
- Q: Hot keys? → A: A handful of keys (feature flags) draw about 5% of all reads.
- Q: Build or buy? → A: Assume off-the-shelf stores were rejected for cost and control; focus on the design, not on selecting a vendor.

## Back-of-envelope
- Traffic: 500K ops/s peak = 400K reads + 100K writes.
- Replica operations (N = 3 replicas, W = 2 write acks, R = 2 read replicas): each read touches 2 replicas = 800K replica reads/s; each write goes to all 3 replicas = 300K replica writes/s. Total about 1.1M replica operations/s.
- Nodes by throughput: assume one NVMe node sustains about 30K operations/s within the latency target. 1.1M / 30K ≈ 37 nodes. To survive losing one of three availability zones at peak, the remaining two thirds must carry the load: 37 / (2/3) ≈ 56, so plan about 60 nodes.
- Storage: 50 TB x 3 replicas = 150 TB raw. LSM-tree compaction needs free space, so plan for about 60% fill: about 250 TB provisioned. Per node: 150 TB / 60 nodes = 2.5 TB of data on an 8 TB NVMe drive (31% full, well below the limit).
- Bloom filters: 50B keys x 3 replicas = 150B key copies; spread across 60 nodes is 2.5B keys per node. At about 10 bits per key (about 1% false positives) that is 25 Gbit ≈ 3.1 GB of memory per node.
- Write-ahead log: 100K writes/s x 1 KB = 100 MB/s cluster-wide; per node (x3 / 60) about 5 MB/s sequential, negligible.
- Network: replication 100K x 1 KB x 3 = 300 MB/s; reads about 800 MB/s; together about 6–9 Gbps cluster-wide, roughly 0.1 to 0.15 Gbps per node.
- Anti-entropy: with Merkle trees of 1M buckets per 1B keys, a mismatch is repaired by exchanging roughly 1,000 keys per bucket, so sync cost is proportional to the difference, not the data size.

## Reference design
**Core API:**
- `put(key, value, ttl?, context?) -> {version context}`
- `get(key, consistency = one | quorum) -> {values[], context}` (more than one value is returned when concurrent writes conflict)
- `delete(key)` (writes a tombstone)

**Data model:**
- Record: `{key, value, version metadata, expires_at, tombstone}`.
- Version metadata: a timestamp for last-writer-wins tenants, or a version vector for merge tenants.
- Per-node storage (log-structured): commit log, in-memory table (memtable), immutable sorted files (SSTables) with Bloom filters, background compaction.
- Placement: keys hashed onto a ring (or into a fixed set of partitions); each key is stored on N = 3 replicas in distinct availability zones.

**Components:**
- Client library or coordinator that routes requests.
- Membership and failure detection by gossip.
- Replica nodes with the storage engine above.
- Hinted handoff for short outages.
- Anti-entropy using Merkle trees for permanent divergence.
- Read repair on quorum reads.
- Compaction and tombstone garbage collection.
- Per-tenant quota enforcement and metrics.

**Write path:** coordinator picks the N replicas, sends the write to all, acknowledges when W = 2 respond. Each replica appends to the commit log, updates the memtable, and flushes to an SSTable when the memtable fills.

**Read path:** coordinator asks R = 2 replicas (or one for `one`); each replica checks the memtable, then Bloom filters to find candidate SSTables, then reads them. The coordinator returns the newest version (or all conflicting versions) and repairs stale replicas in the background.

**Key trade-offs and rejected alternatives:**
- Availability-leaning (Dynamo-style, tunable quorum) vs consensus-based (a Raft or Paxos group per partition): chosen the first because writes must succeed during failures. Offer a separate, slower linearizable tier for tenants who need it.
- Last-writer-wins vs version vectors: LWW is simple but silently drops concurrent writes and depends on clocks; use version vectors only for tenants that need merging.
- Ring with virtual nodes vs fixed number of partitions: both work; fixed partitions make moving data and balancing simpler to operate (see case 03).
- Log-structured storage vs B-tree: LSM chosen for the write rate and sequential disk writes; cost is read amplification (offset by Bloom filters) and compaction overhead.
- Client-side routing vs a coordinator tier: client-side saves a hop but spreads ring logic into every client; start with a thin coordinator, move to smart clients if latency demands.
- Strict quorums vs sloppy quorums with hinted handoff: sloppy quorums keep accepting writes during failures but give up the guarantee that reads see the latest write.

## Deep-dive topics
- With N = 3, W = 2, R = 2, can a read ever return stale data? Walk through the failure cases.
- How are concurrent writes to the same key detected and merged, and how do you stop the version metadata from growing?
- What exactly does hinted handoff do, what happens if the node holding the hints dies, and how does anti-entropy back it up?
- How do deletes work, and what goes wrong if a tombstone is garbage-collected before a lagging replica sees it?
- How do you detect that a node is down without false alarms, and what do you do about a node that is slow but not dead?
- How do you isolate tenants so one team's traffic spike does not hurt others?
- How do you serve a feature-flag key that gets 5% of all reads?
- How do you add 20 nodes without hurting latency, and how do you rebalance data safely?

## Curveballs
- Traffic grows 100x. Which resource saturates first (disk, network, memory) and how do you scale it?
- An entire availability zone is lost. What happens to writes, reads and durability?
- Product wants active-active writes in two regions. What changes and what conflicts appear?
- A tenant now needs multi-key transactions and secondary indexes. What do you tell them?
- A node's disk is destroyed. Describe the recovery and how long the data is under-replicated.

## Red flags
- Claiming to deliver consistency, availability and partition tolerance all at once, or answering only with "CAP says pick two".
- Saying W + R > N guarantees strong consistency without mentioning sloppy quorums, concurrent writes or failed partial writes.
- Resolving conflicts with last-writer-wins without noting lost writes and clock skew.
- Ignoring deletes, tombstones and compaction.
- Using one all-to-all heartbeat design with hundreds of nodes.
- Putting all replicas in one availability zone.
- No story for node addition, repair or hot keys.
- Designing the storage engine in detail but never stating the consistency model.
- Common misconception: presents CAP as pick two of three and labels stores CP or AP; partitions are not optional, so the real choice is consistency or availability during a partition (PACELC adds latency versus consistency otherwise)
- Common misconception: claims W + R > N guarantees strong consistency; sloppy quorums, concurrent writes, partial write failures and restores from old replicas can still return stale reads
- Common misconception: says Cassandra resolves conflicts with vector clocks; its conflict resolution is last-writer-wins by timestamp
- Common misconception: calls vector clocks what are really version vectors, or truncates the oldest entries and accepts the false conflicts that creates
- Common misconception: says replication is asynchronous yet waits for W acknowledgements; writes to W replicas are synchronous from the coordinator's view and the rest are asynchronous
- Common misconception: calls quorum reads and writes consensus; they are not consensus in the Paxos/Raft sense
- Forgets tombstones and compaction when describing deletes

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Single hash table, replication hand-waved | Partitions by hash, replicates N ways, explains W/R/N trade-offs, memtable plus SSTable write path | Computes node count and storage, explains Bloom filters and read path, names consistency model explicitly |
| Senior | Treats quorum as a guarantee; no failure story | Covers version vectors vs LWW, gossip, hinted handoff, anti-entropy, tombstones | Explains where quorum guarantees break, sizes memory and compaction overhead, designs per-tenant isolation and hot-key handling |
| Staff | Picks a technology by name | Frames consistency tiers by tenant need and justifies the cost of each | Plans repair, rebalancing and disaster recovery operationally, quantifies failure-domain math, and argues build versus buy with total cost |
