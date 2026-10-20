# 03 Consistent hashing

## Opening prompt
"We spread cached data across a pool of servers with `hash(key) % N`. Every time we add or remove a server, our database gets crushed. Fix it."

## Hidden requirements
- Q: What is being distributed? → A: Cache entries for a read-heavy product catalog; the team may reuse the scheme for a sharded datastore later.
- Q: What scale? → A: 50 cache nodes today, up to 500 within two years; 1B keys of about 1 KB each (about 1 TB); 2M lookups/s at peak. [critical]
- Q: Who performs the lookup? → A: A client library inside about 300 application services; a proxy tier is acceptable only if you justify it. [critical]
- Q: How often does membership change? → A: Autoscaling changes the pool several times a day, one node fails per week on average, and deploys restart nodes in rolling fashion.
- Q: What counts as acceptable data movement per change? → A: Close to the theoretical minimum, about 1/N of keys per node change; the pain is a cold-cache stampede on the database. [critical]
- Q: Balance requirement? → A: No node should carry more than 1.25x the average keys or traffic.
- Q: Are nodes identical? → A: No; two sizes, with the large instance holding 2x the memory of the small one.
- Q: Are there hot keys? → A: Yes; the top 0.01% of keys draw about 20% of traffic (popular products). [critical]
- Q: Is replication needed? → A: For the cache, optional for hot data; if the scheme is reused for a durable store, 3 replicas across availability zones.
- Q: How consistent must clients' views of membership be? → A: Clients may disagree for up to 30 s (extra misses are acceptable); stale values are tolerated up to the 5-minute TTL.
- Q: Latency budget? → A: Node selection under 10 microseconds; no added network hop above 1 ms.
- Q: Geography? → A: One region, three availability zones.
- Q: Compliance? → A: Cached values can contain personal data, so nodes must stay in-region; no other constraint on the scheme.
- Q: Availability? → A: Losing any single node must not cause user-visible errors (99.95% target).
- Q: Memory limits on clients? → A: Ring metadata must stay under 100 MB per client process.

## Back-of-envelope
- Why modulo fails: going from N to N+1 servers, a key keeps its server only if `h mod N = h mod (N+1)`. That holds for N out of every N(N+1) hash values, so the fraction that stays is 1/(N+1) and the fraction moved is N/(N+1). For 50 to 51 servers: 50/51 ≈ 98% of keys move. Removing one of 50: 49/50 = 98%.
- Why a ring works: only the keys adjacent to the new node move, about 1/(N+1) = 1/51 ≈ 2% of 1B keys ≈ 20M keys (about 20 GB).
- Database impact (assume 95% normal hit ratio, 2M lookups/s, uniform access): normal miss traffic = 5% x 2M = 100K QPS. After a modulo reshuffle, miss rate ≈ 5% + 95% x 98% ≈ 98%, or about 1.96M QPS (20x normal). After a ring change, miss rate ≈ 5% + 95% x 2% ≈ 6.9%, or about 138K QPS (about 1.4x normal).
- Balance: with v virtual nodes per server placed randomly, each server's share has relative standard deviation of about 1/sqrt(v): v = 100 gives about 10%, v = 200 about 7%, v = 1,000 about 3%. To keep the busiest node near 1.25x average, use at least 200 virtual nodes per small server and 400 per large server (weights).
- Ring size: 500 servers x 200 virtual nodes = 100,000 points x about 16 B (8 B hash + node id) ≈ 1.6 MB, far under 100 MB. A lookup is a binary search over 100K points: about 17 comparisons, well under 10 microseconds.
- If this were a durable store: moving 20 GB over a 10 Gbps link takes about 16 s at line rate (20e9 x 8 / 10e9); in practice throttled to minutes to protect live traffic.

## Reference design
**Core API** (client library):
- `getNode(key) -> node` and `getReplicas(key, r) -> [node]`
- `addNode(node, weight)` and `removeNode(node)` called by a membership service, not by application code.
- Membership service publishes versioned node lists.

**Data model:**
- Node record: `{id, address, zone, weight, vnode_count}`.
- Ring: sorted array of `(hash64, nodeId)` points built as `hash(nodeId + "#" + i)` for i in 0..vnode_count-1.
- Key placement: hash the key with the same function, binary-search for the first point at or after it, wrapping to the start; that point's node owns the key.
- Replication: walk clockwise to the next R distinct physical nodes, skipping nodes in an availability zone already used.

**Components:** client library with a local ring, membership service (a coordination service or gossip) that pushes versions, cache node fleet, metrics on per-node key count and QPS.

**Hash function:** a fast non-cryptographic 64-bit hash (for example MurmurHash3 or xxHash) is enough; strong cryptographic hashing is unnecessary.

**Key trade-offs and rejected alternatives:**
- `hash % N` rejected: 98% of keys move on any change.
- Plain ring without virtual nodes rejected: arc sizes vary wildly, and removing a node dumps its whole load on one neighbor.
- Ring with virtual nodes (chosen for the cache): minimal movement, weights for mixed hardware, simple client-side lookup.
- Fixed hash slots (as in Redis Cluster's 16,384 slots) or a fixed number of partitions: strong alternative, and the usual choice for databases; moves whole partitions and needs an explicit slot map. Prefer it for the durable store.
- Rendezvous (highest-random-weight) hashing: no ring state and trivial weights, but lookup is O(N); fine for tens of nodes, wasteful at 500 nodes and 2M lookups/s.
- Jump consistent hash: no memory and perfectly even, but nodes can only be added or removed at the end; unsuitable for arbitrary failures.
- Bounded-load consistent hashing: caps per-node load by spilling to the next node; consider it if balance matters more than perfect locality.

## Deep-dive topics
- How many virtual nodes, and how do you justify the number with balance versus memory?
- How do you handle a node that is unhealthy for 30 seconds: remove it from the ring, or wait? What happens to its keys during that time?
- Hot keys: does consistent hashing help with a key that gets 20% of traffic? What does?
- How do clients with different views of the ring behave during a membership change, and how do you bound the damage?
- How do you warm a new node so the database is not stampeded while it fills?
- How do you pick replicas so that two copies never share an availability zone?
- When would you choose fixed slots over a ring, and what do you change in the design?
- How do you rebalance when one node is overloaded but not failing?

## Curveballs
- The pool grows from 50 to 500 nodes in a week. What changes in the ring and in the database load?
- An availability zone (one third of nodes) disappears at once. What happens to keys, load and the database?
- One product's key receives 100K requests/s. Walk through your fix.
- The cache turns into a durable key-value store with replication. What do you add?
- Half the nodes are replaced by machines with 4x the memory. How do weights change?

## Red flags
- Proposing `hash % N` or not recognizing why it breaks on resize.
- Drawing a ring with one point per server and claiming it is balanced.
- Claiming consistent hashing eliminates hot keys.
- Placing replicas on neighboring virtual nodes that belong to the same physical server.
- Ignoring how clients learn about membership changes.
- Using a language's built-in hash function that differs between processes or restarts, so clients disagree on key placement.
- Removing a node from the ring at the first failed health check.
- Common misconception: claims consistent hashing solves the hot-key problem; it spreads keys evenly but one hot key still lands on one node, so hot keys need replication, local caches or key salting
- Common misconception: assumes 200 virtual nodes give about 5% load deviation; the simple model gives roughly 1/sqrt(v), about 7%
- Common misconception: believes a cryptographic hash is required for ring placement; any well-distributed hash works, but language built-in hashes that differ between processes break client-side placement
- Common misconception: assumes databases use a classic hash ring; many use fixed or splittable partitions, and the ring with virtual nodes suits caches and CDNs best

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Describes modulo only, or draws a ring with no reasoning about movement | Explains the ring, clockwise lookup, virtual nodes and why only about 1/N keys move | Quantifies the movement and the database impact, and sizes the ring and lookup cost |
| Senior | Does not address weights, replication or membership | Adds weights, replicas in distinct zones, a membership service, and failure handling with grace periods | Explains balance statistics (about 1/sqrt(v)), distinguishes ring from fixed-slot designs, and separates hot-key handling from distribution |
| Staff | Treats it as a textbook algorithm | Chooses among ring, slots, rendezvous and jump hashing for the actual workload and explains why | Plans migration, warm-up and rollback in production, designs for divergent client views, and challenges whether the term "consistent hashing" fits the system |
