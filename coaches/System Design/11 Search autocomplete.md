# 11 Search autocomplete

## Opening prompt
"Design the search-as-you-type box for a large search product. As the user types, show the five most likely completions. It has to feel instant."

## Hidden requirements
- Q: Is matching prefix-only, or can it match in the middle of a query? → A: Prefix only. Infix matching and typo tolerance are explicitly out of scope, though the candidate should say what they would cost. [critical]
- Q: How many suggestions, and how are they ranked? → A: Five. Ranked by popularity, meaning historical query frequency. Personalization is a follow-up.
- Q: What latency budget? → A: End-to-end p99 under 100 ms from keystroke to rendered list, which leaves roughly 10–20 ms server-side once network time is subtracted. [critical]
- Q: What scale? → A: 10M DAU, 10 searches per user per day. The candidate must also decide how many requests each search generates (every keystroke is the naive answer). [critical]
- Q: What input alphabet? → A: Lowercase English letters only. Multi-language, Unicode and normalization are a follow-up.
- Q: How fresh must suggestions be? → A: Daily or weekly refresh is acceptable for the base design. Trending queries within minutes is the stretch goal and requires a different pipeline.
- Q: Are there content rules? → A: Yes. Hateful, violent, sexual and otherwise unsafe suggestions must be removable quickly, without waiting for a rebuild. [critical]
- Q: Where does the data come from? → A: Query logs from the main search product. The candidate owns the pipeline from logs to suggestions.
- Q: Is the same top five shown to everyone? → A: Yes for now. Per-country or per-language variants are a follow-up that affects the sharding story.
- Q: Availability expectation? → A: If autocomplete fails, search itself must still work. Degrading to no suggestions is acceptable, a slow or broken search box is not.
- Q: How big is the set of distinct queries? → A: Open-ended. The candidate should assume a long tail where most distinct strings occur once, and decide how to prune it.
- Q: Does the system need to return something for any prefix? → A: Every prefix of reasonable length. Empty or very short prefixes (one letter) are the highest-traffic case and should be precomputed.
- Q: Is privacy a concern? → A: Yes. Raw queries can contain personal data, so thresholds before a query becomes eligible for suggestion matter.

## Back-of-envelope
- Naive request volume: 10M users × 10 searches × 20 keystrokes per search = 2B requests per day, about 23K per second on average (commonly rounded to 24K) and about 48K per second at 2× peak.
- Realistic request volume: clients debounce (about 100–150 ms), skip the first keystroke or two, and reuse browser and in-memory cached responses. That cuts requests to about 6–8 per search. 10M × 10 × 7 = 700M per day, about 8K per second on average and about 20–25K per second at peak. Always show both numbers and say which assumption moved.
- Cache hit rate: prefix traffic is extremely skewed because short prefixes dominate. A CDN or edge cache with a 1 hour TTL plus a server-side cache should absorb a large fraction (say 60–80%) of requests, leaving the backend with around 5–10K per second at peak.
- New data per day: 10M × 10 queries × 20 B × 20% new = 0.4 GB per day. Raw logs are much larger: 100M queries per day × about 100 B per log line = 10 GB per day uncompressed.
- Index size: assume 50M distinct queries retained after pruning, average 20 characters. A plain trie could need up to about 1B nodes, but a compressed (radix) trie needs on the order of 100M. Storing five suggestion IDs per node (4 B each) plus pointers is about 60 B per node, giving about 6 GB. Even with 3× overhead this fits in the memory of one machine. Capacity does not force sharding at this scale. Throughput and availability do.
- Alternative layout: precomputed map from prefix to top-5 list. Limiting prefix length to 30–50 characters and deduplicating, about 300M prefix keys at about 100 B each is 30–50 GB. Fits a small cluster.
- Rebuild cost: a weekly batch over about 70 GB of weekly aggregated counts is trivial for a small MapReduce or Spark job, so a daily rebuild is affordable too.
- Response size: five strings of about 20 B plus framing is roughly 200–300 bytes. Bandwidth is negligible. Request rate and tail latency are what matter.

## Reference design
**Core API.**
- GET /suggest?q=<prefix>&k=5 returns an ordered list of up to five strings. Optional parameters: locale, client version.
- Responses carry Cache-Control: public or private with a short max-age (a common setting is private with 3600 s). A shared-cache TTL of minutes is a good default.
- Internal: a log ingestion path (append-only query log) and an admin path for blocklist updates.

**Data model.**
- Aggregated counts: (query, week or day bucket, count) in a columnar store or object storage as batch output.
- Serving structure: a trie (ideally compressed) where each node stores its own top-5 completions, or equivalently a key-value map from prefix to top-5 list. Store IDs or compact references, not duplicated strings.
- Blocklist: a small set of normalized strings and patterns, held in memory on every serving node and updated by push.
- Version: each serving structure is immutable and has a version number, which allows atomic swap and instant rollback.

**Components.**
- Collection: search servers write query events to a log (Kafka or similar), with sampling for non-critical analytics and no sampling for abuse signals.
- Aggregation: a batch job (daily or weekly) counts queries, drops the long tail below a threshold, normalizes text, and removes unsafe or private strings.
- Builder: a worker converts aggregated counts into the serving structure (including top-5 at every node) and writes the artifact to blob storage with a version.
- Serving: stateless API servers load the current artifact into memory (or fetch from a cache tier), look up the prefix and return the stored list. Servers are replicated for throughput and availability.
- Filter layer in front of the suggestion list removes blocklisted items at read time, so takedown does not wait for the next build.
- Client: debounce, minimum prefix length, in-memory and HTTP caching, cancellation of in-flight requests when new keystrokes arrive.
- Trending path (stretch): a streaming job with time-decayed counts (sliding window or exponential decay) over approximate heavy-hitter sketches (count-min sketch plus Space-Saving), whose top items are merged with the batch result at query time.

**Key trade-offs and rejected alternatives.**
- Database query with LIKE prefix and ORDER BY frequency: simple but cannot meet the latency target at scale. Rejected after the data outgrows an indexed table.
- Plain trie with subtree traversal at query time: cost is proportional to the subtree under the prefix, which is huge for short prefixes. Precomputing top-k per node trades memory for O(prefix length) lookups.
- Real-time updating of the live trie: every query would have to update all ancestor nodes and the top-k lists. Rejected in favour of immutable rebuilds and atomic swap, with a separate trending layer for freshness.
- Sharding by first letter: produces skewed shards and hot letters. Prefer replication (the whole index fits in memory) or, if sharding is needed, hash of a short prefix or a frequency-aware shard map.
- Elasticsearch or Lucene completion suggesters (finite state transducers) are a legitimate build-versus-buy answer, and a strong candidate should mention them.
- Freshness versus cost: a weekly rebuild is cheapest but misses news events. Daily rebuild plus a trending side channel covers most products.

## Deep-dive topics
- Why store the top five at every trie node, and what does that cost in memory and in update complexity?
- How would you cut per-search request volume from about 20 to about 7 without hurting the experience?
- How do you swap in a freshly built index across hundreds of servers atomically, and roll back if quality drops?
- How do you remove a harmful suggestion within minutes, without waiting for the next rebuild?
- Does this index need to be sharded at all, and if so by what key and how do you avoid hot shards?
- How do you surface trending queries within minutes without destroying the stable ranking?
- How do you prevent query poisoning (coordinated bots pushing a string to the top) and the leakage of private queries?
- How do you extend to multiple languages and countries, and what happens to index size and sharding?

## Curveballs
- A breaking news event makes a brand new query the most searched string in the world within ten minutes. Ask what the user sees now and what the pipeline must change to react.
- Ask for per-user personalization on top of global popularity. Expect discussion of merging a personal list with the global list within the latency budget, and of privacy.
- Requests triple during a sports final. Ask what you scale and what you cache, and whether the answer changes for one-character prefixes.
- A legal team demands a specific person's name be removed from suggestions everywhere within an hour. Ask how the filter layer, caches (CDN, browser) and the next build all comply.
- Ask for typo tolerance ("did you mean"). Expect a clear statement that this is a different problem (edit distance, n-gram or phonetic indexes) and what it adds.

## Red flags
- Jumping to a SQL LIKE query or a full-text engine without discussing latency and request volume.
- Taking "one request per keystroke" as fixed and ignoring debouncing and caching.
- Updating the production trie synchronously on every search.
- Sharding by first letter with no mention of skew.
- No plan to remove unsafe suggestions between rebuilds.
- Treating the trie as the whole answer, with no data pipeline, versioning or rollback.
- Quoting an O(p) + O(c) + O(c log c) complexity with c as the node's immediate children without noticing that collecting all completions visits the whole subtree.
- Common misconception: states the unoptimized prefix query as O(p) + O(c) + O(c log c) with c as the node's children; collecting all completions visits the whole subtree under the prefix, which can be far larger
- Common misconception: sizes load as every keystroke being a request (about 24K QPS); real clients debounce and cache, so the effective rate is several times lower
- Common misconception: updates a trie node and its ancestors with the new count and calls it done; ancestors hold top-k lists, so a dropped count or removed item may need a replacement from outside the list, which is why immutable rebuilds are preferred
- Common misconception: shards the trie by first letter (a-m, n-z) with a shard map; at these sizes the structure fits in one machine's memory, so replication is the real need
- Ignores query poisoning and abuse, privacy thresholds before a query becomes suggestible, and atomic index swap and rollback

## Rubric signals
- Mid, weak: describes a trie and lists "cache it" but cannot explain how counts get into the structure or how top results are found quickly.
- Mid, hire: correct trie with top-k per node, separate offline aggregation pipeline, cache in front, sensible QPS estimate.
- Mid, strong-hire: adds client debouncing, atomic version swap, a blocklist filter, and notices the dataset fits in memory.
- Senior, weak: follows the textbook and shards by first letter without checking whether sharding is needed.
- Senior, hire: compares rebuild cadence to freshness needs, sizes the index, plans replication and versioned rollout, and handles takedowns.
- Senior, strong-hire: proposes a trending side channel with decayed counts and approximate heavy hitters, discusses poisoning and privacy thresholds, and knows when to buy (Lucene FST) versus build.
- Staff, weak: optimizes the data structure while ignoring product questions such as personalization, languages and measurement of quality.
- Staff, hire: ties the architecture to quality metrics (suggestion acceptance rate, keystrokes saved), designs experimentation and safe rollout, and covers multi-region serving.
- Staff, strong-hire: reasons about cost per thousand requests, edge caching strategy by prefix length, abuse economics, and a roadmap from batch to streaming without a rewrite.
