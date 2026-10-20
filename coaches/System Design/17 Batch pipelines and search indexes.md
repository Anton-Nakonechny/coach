# 17 Batch pipelines and search indexes

## Opening prompt
"Our shop has a 500 million product catalog and a couple of terabytes of click and order logs every day. Design the offline pipeline that builds the product search index and the 'customers also bought' tables each night, and gets them safely into production."

## Hidden requirements
- Q: What are the outputs and who consumes them? → A: Two derived datasets: a full-text product search index served by a search cluster, and per-product recommendation lists served from a key-value store. Online services only read these, they never write them. [critical]
- Q: How big are the inputs? → A: A catalog of about 500M products at roughly 2 KB each (about 1 TB), and about 2 TB of click and order events per day (about 8B events at 250 B each). The pipeline joins a rolling window (say 14 days, about 28 TB) with the catalog.
- Q: What is the freshness requirement? → A: New data must be live by 06:00 local time each day, with a run window of 2–3 hours. Real-time freshness for price and stock is handled separately (a stream path, case 18), and the batch index must tolerate that.
- Q: What happens if a run fails or produces bad data? → A: The previous good version must keep serving, and rolling back to it must be a metadata flip, not a rebuild. A bad run must never partially overwrite production. [critical]
- Q: Is the pipeline allowed to modify its input? → A: No. Inputs are immutable and the pipeline only reads them. Outputs are written to new versioned locations. This makes reruns, debugging and backfills safe.
- Q: How do jobs get scheduled and how do dependencies work? → A: A workflow scheduler runs a DAG of jobs. A job starts only when its input datasets are complete (not merely started). Failed jobs retry without rerunning completed ones.
- Q: How skewed is the data? → A: Very. A few products or sellers account for a large share of events (a viral product might have 1% of all clicks, about 80M events). Joins and group-bys on such keys create stragglers and out-of-memory failures. [critical]
- Q: How is the data joined, and with what cost? → A: Clicks and orders are joined with the catalog and with each other. The candidate must pick join strategies (shuffle-based sort-merge, broadcast of a small side, bucketed pre-partitioned inputs) with their costs.
- Q: Are reruns deterministic? → A: They should be. The same inputs and code give the same outputs, so a retry or a rebuild produces identical results, with fixed seeds and no dependence on the wall clock (the run's logical date is a parameter). [critical]
- Q: How are search index updates handled? → A: Build full index segments offline for a version, or build only for changed documents and merge. The candidate must justify the choice.
- Q: How do outputs reach the key-value store? → A: By building the store's files inside the batch job and loading them atomically, or by writing in bulk through a controlled loader. Row-by-row writes from thousands of tasks would hurt the online workload.
- Q: What data quality checks run? → A: Row counts versus yesterday, null rates, join match rates, distribution checks, and a sample of known queries compared with the previous index. Failing a check stops the publish.
- Q: How is cost controlled? → A: Cluster sized for the nightly window and released afterwards (or shared with other jobs). The candidate should estimate machine-hours and say which stages dominate.

## Back-of-envelope
- Data volume: catalog 500M × 2 KB = 1 TB. Daily logs 2 TB, so a 14-day window is 28 TB. Compressed columnar storage (Parquet or similar) typically cuts this by 3–5×, so scans read much less than the raw figure.
- Ideal scan time: 200 worker nodes × 200 MB/s of local sequential read is about 40 GB per second aggregate. Reading 3 TB (1 day of logs plus catalog) takes about 75 seconds of pure I/O. The real run takes much longer because of shuffles, spills, retries and stragglers, plan for 30–60 minutes per large join stage.
- Shuffle: a reduce-side join of 2 TB of events with 1 TB of catalog moves about 3 TB across the network. At 200 nodes with 10 Gbps links (about 250 GB per second theoretical) the network is not the limit. Disk spill and skew are.
- Skew example: a key with 80M events at 250 B is 20 GB for one reducer. At 50 MB/s processing speed that single task runs about 400 seconds and the stage cannot finish before it does. If the key were 10× bigger, one task would take more than an hour or run out of memory.
- Task failures: a 28 TB job might have about 20,000 tasks. At a 0.1% task failure rate there are about 20 retries per run, harmless if tasks are idempotent and inputs are immutable. A whole-job failure rate of a few percent is acceptable only if reruns fit inside the window.
- Index size: an inverted index is typically 30–50% of indexed text size plus stored fields and per-document values. For 1 TB of documents, assume about 0.5–1 TB. Shard to about 10–50 GB per shard: 40 shards of about 25 GB. With 3 replicas, about 3 TB of serving storage.
- Query fan-out: 5K search queries per second at peak, each scatter-gathered to all 40 shards, is 200K shard-queries per second. If one shard replica serves about 1K per second, about 200 replicas are needed, which is 5 per shard rather than the 3 assumed above, so replica count is set by query load, not just by durability. Check with real benchmarks. This also shows the tail-latency problem: the slowest of 40 shards decides the response time.
- Recommendation table: 100M users or 50M products with 20 recommendations of 16 bytes each is about 16–32 GB. Writing 100M rows at 50K writes per second takes about 33 minutes and competes with live reads. Building the store's files offline and swapping them in takes seconds on the serving side.
- Incremental option: if 1% of documents change daily, 5M documents are re-indexed instead of 500M. That is 100× less indexing work, but segment merges, deletes and the loss of simplicity must be accepted.
- Run window: a 3-hour window with a 1-hour margin leaves 2 hours of compute. If the full pipeline needs 400 node-hours, then 200 nodes finish in 2 hours with no slack for a failed stage. This is why checkpointing between stages matters.

## Reference design
**Core API.**
- No public API for the batch side, but a clear contract: dataset names, versions (by run date), schemas, completion markers.
- Serving side: search query API (query, filters, page) hitting the search cluster. Recommendation lookup (product_id returns list) from a key-value store.
- Operational API: publish(dataset, version), rollback(dataset, version), status(run_id).

**Data model.**
- Raw event logs, partitioned by date, immutable, columnar format. Catalog snapshot, partitioned by date, immutable.
- Intermediate datasets (cleaned events, sessionized events, product co-occurrence counts) written to the distributed file system or object storage, each with a version and a success marker.
- Search index: versioned set of immutable segments per shard, plus a manifest listing the current version.
- Recommendation output: key-value files built per partition, versioned.
- Run metadata: run id, logical date, input versions, code version, output versions, quality check results.

**Components.**
- Distributed storage (HDFS or object storage) for inputs, intermediates and outputs.
- Compute engine: MapReduce-style or dataflow engine (Spark, Flink batch). Stages: clean and deduplicate events, join events to catalog (broadcast or shuffle), aggregate to product-pair co-occurrence counts, compute top-N per product, build search documents (enrich with popularity signals), build index segments per shard.
- Workflow scheduler running the DAG, retrying failed tasks and tracking completion markers.
- Quality gate comparing outputs with previous versions and thresholds. Publishing only if it passes.
- Publisher: copies or points the serving fleet to the new version, then flips a pointer atomically (alias swap on the search cluster, version pointer for the key-value store). The old version is kept for fast rollback and deleted after a retention period.
- Serving: search cluster (Lucene-based: Elasticsearch, Solr or OpenSearch), key-value store reading prebuilt files.

**Key trade-offs and rejected alternatives.**
- Batch (nightly rebuild) versus streaming (continuous indexing): batch is simpler, deterministic, easy to rerun and fix by rebuilding, but stale for up to a day. Streaming gives freshness but harder correctness. A common answer is batch as the source of truth plus a small stream path for fast-changing fields (price, stock) merged at query time, which is the lambda-style combination discussed in DDIA ch.12.
- Full rebuild versus incremental: full rebuild is simple and self-healing (a bug fix repairs everything on the next run), incremental is cheaper but accumulates errors and needs merges and deletes.
- Reduce-side (shuffle) join versus map-side or broadcast join: use broadcast when one side fits in memory (a small dimension table), use a shuffle join for two large datasets, and pre-partition (bucket) both on the join key to avoid repeated shuffles.
- Skew handling: sample to find hot keys, split them across tasks and replicate the matching rows from the other side (salting), or treat hot keys separately. Doing nothing leaves stragglers that dominate the run time.
- Build the output inside the job and swap, versus writing records to the online store from tasks: building and swapping avoids load on the live store and makes the update atomic and reversible, writing directly risks partial state and overload.
- Materialize intermediates to durable storage versus pipelining operators in memory: materialization allows retry of one stage and reuse, pipelining is faster but makes a failure restart more work. Modern dataflow engines mix the two with checkpoints.
- Search cluster as the system of record: rejected. It is a derived index, the source of truth is the catalog and event data, so the index can always be rebuilt.

## Deep-dive topics
- Why must batch jobs treat inputs as immutable and write only new output versions, and what does this buy for retries and rollbacks?
- How do you detect and fix a skewed join key, and what do the alternatives cost?
- How do you choose between a shuffle join, a broadcast join and a pre-bucketed join for each stage?
- How do you publish a new index version atomically, verify it, and roll back in seconds?
- How do you build and load the recommendation store without hammering it?
- What does an inverted index look like on disk, why are segments immutable, and how do deletes and merges work?
- How would you add a freshness path for price and stock without breaking the batch design?
- How do you keep reruns deterministic, and what breaks determinism (wall-clock time, random sampling, nondeterministic joins)?

## Curveballs
- The nightly job finishes 4 hours late because one task ran for hours. Ask for diagnosis steps and structural fixes (skew, speculative execution, stage splitting).
- A bug in tokenization has been shipping bad index versions for three days. Ask how you rebuild each day's version, compare and recover without touching live traffic.
- Product asks for the new index to be only 15 minutes stale. Ask what changes: stream processing, incremental segments, dual-path merges, and what correctness is given up.
- The search cluster loses a shard replica during peak traffic after a new version is swapped in. Ask how you protect capacity during swaps (rolling, warm-up, keeping both versions loaded).
- Storage cost grows linearly with retained versions. Ask how you decide retention for intermediates, outputs and raw logs.

## Red flags
- Overwriting the live index or table in place from a running job.
- Mutable inputs, or jobs with side effects on production systems from inside tasks.
- Ignoring skew and assuming data spreads evenly across partitions.
- No quality gate or rollback path.
- Writing 100M rows one at a time to the online store.
- Using the search cluster as the only copy of the data.
- No scheduler thinking: no dependency handling, retries or completion markers.
- Proposing streaming everywhere without saying what it costs in correctness and operations.
- Common misconception: says batch means Hadoop MapReduce; new work uses object storage with columnar formats, dataflow engines (Spark, Flink, warehouse engines) and table formats such as Iceberg and Delta, though immutability, determinism, skew handling and atomic publish still hold
- Common misconception: insists batch and stream must be separate worlds joined by a lambda architecture; many teams use one engine for both or incremental table formats
- Builds search indexes with MapReduce by default when many teams use the search engine's own indexing pipeline from a stream or snapshot
- Ignores data quality gates and the operational side of publishing and rollback

## Rubric signals
- Mid, weak: describes a cron script that queries the database and updates the search index row by row.
- Mid, hire: separates raw data, batch compute and serving, uses a distributed file system and a scheduler, builds a new index and swaps it in.
- Mid, strong-hire: explains immutable inputs and versioned outputs, atomic swap and rollback, and sizes the data and the cluster.
- Senior, weak: can name MapReduce or Spark but cannot explain a join strategy or why a stage is slow.
- Senior, hire: handles skew, chooses join strategies, plans quality gates, and designs the bulk load path for the key-value store.
- Senior, strong-hire: reasons about determinism and reruns, sizes shards and fan-out tail latency, and combines batch with a fast path with clear ownership of each field.
- Staff, weak: optimizes one job without owning the platform concerns (cost, SLAs, data quality, ownership).
- Staff, hire: sets SLAs and error budgets for data freshness, designs backfill and reprocessing as first-class operations, and manages cluster cost.
- Staff, strong-hire: decides when to migrate parts to streaming or incremental processing based on measured value, designs lineage and observability across datasets, and anticipates schema evolution across teams.
