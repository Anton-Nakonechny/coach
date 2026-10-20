# 00 Numbers cheat sheet

Purpose: one place for the numbers an interviewer persona and a candidate should have at hand for back-of-the-envelope work. "Case n" means a figure used in design case n. "Rule of thumb" means an assumption, which varies by hardware and implementation and should be quoted as an estimate, never as a fact.

## 1. Units and powers of two 
| Power | Approx. | Name |
|---|---|---|
| 2^10 | 1 thousand | 1 KB |
| 2^20 | 1 million | 1 MB |
| 2^30 | 1 billion | 1 GB |
| 2^40 | 1 trillion | 1 TB |
| 2^50 | 1 quadrillion | 1 PB |

A byte is 8 bits. An ASCII character is 1 byte. Write units next to every number (5 KB or 5 MB?).

## 2. Time conversions
| Quantity | Value |
|---|---|
| Seconds per day | 86,400 (round to about 10^5 for mental math) |
| Seconds per month | about 2.6 million |
| Seconds per year | about 31.6 million |
| 1 million events per day | about 12 per second |
| 1 billion events per day | about 11,600 per second |
| Peak versus average | A common rule of thumb is 2× average. The design cases use 2.5× to 4× for spiky traffic (case 14: 12K vs 4.6K, case 15: 20K vs 5K, case 18: 10K vs 3K). State your factor. |

Formula: QPS = daily active users × actions per user per day / 86,400.

## 3. Latency numbers 
Dean's numbers are from 2010. The second column shows approximate 2020 values. Use the order of magnitude, not the digits.

| Operation | 2010 | 2020 |
|---|---|---|
| L1 cache reference | 0.5 ns | 1 ns |
| Branch mispredict | 5 ns | 3 ns |
| L2 cache reference | 7 ns | 4 ns |
| Mutex lock/unlock | 100 ns | 17 ns |
| Main memory reference | 100 ns | 100 ns |
| Compress 1 KB (Zippy) | 10 µs | 2 µs |
| Send 2 KB over the network | 20 µs (1 Gbps) | 44 ns (commodity network) |
| SSD random read | not listed | 16 µs |
| Read 1 MB sequentially from memory | 250 µs | 3 µs |
| Read 1 MB sequentially from SSD | not listed | 49 µs |
| Round trip within the same datacenter | 500 µs | 500 µs |
| Disk seek | 10 ms | 2 ms |
| Read 1 MB sequentially from the network | 10 ms | not listed |
| Read 1 MB sequentially from disk | 30 ms | 825 µs |
| Packet round trip California to Netherlands | 150 ms | 150 ms |

Book conclusions: memory is fast and disk is slow, avoid disk seeks, compression is cheap so compress before sending over the internet, and datacenters in different regions are expensive to talk to.

Rules of thumb used across the cases (assumptions):
| Item | Value | Used in |
|---|---|---|
| fsync on SSD | 0.1–1 ms | case 16 |
| Round trip within a zone | about 0.5 ms | case 16 |
| Round trip across zones in a region | 1–2 ms | case 16 |
| Round trip across regions | 60–100 ms (80 ms in a curveball) | case 16 |
| Consensus write commit in one region | 2–5 ms | case 16 |
| Leader election after crash | 2–5 s without writes | case 16 |
| Sustained writes of one consensus cluster | 10K–50K small writes per second | case 16 |
| Conditional single-row update on a hot row | thousands per second per core | case 15 |
| One log partition | 10–50 MB per second | cases 18, 19 |
| Single-threaded processor with local embedded store | 20K–50K simple operations per second | case 19 |
| Local sequential read per worker node | about 200 MB per second | case 17 |
| Sink write rate of a typical database or index | 50K–100K rows per second (varies a lot) | cases 17, 19 |
| Server memory per idle client connection | about 20 KB | case 19 |

## 4. Availability (weekly and monthly columns are computed)
| Availability | Per day | Per week | Per month | Per year |
|---|---|---|---|---|
| 99% | 14.4 min | 1.68 h | 7.3 h | 3.65 days |
| 99.9% | 1.44 min | 10.1 min | 43.8 min | 8.77 h |
| 99.99% | 8.64 s | 1.01 min | 4.38 min | 52.6 min |
| 99.999% | 864 ms | 6.05 s | 26.3 s | 5.26 min |
| 99.9999% | 86.4 ms | 0.6 s | 2.63 s | 31.6 s |

Chained dependencies multiply: three components at 99.9% give 0.999^3 = 99.7%, about 26 hours per year (case 19). Parallel redundancy multiplies the failure probabilities instead.

## 5. Tail latency and percentiles 
- Use percentiles (p50, p95, p99, p999), not averages. Always say which one the SLO is on.
- If one request calls n backends and each is slow with probability p, the request is slow with probability 1 − (1 − p)^n. For p = 1%: n = 10 gives 9.6%, n = 40 gives 33%, n = 100 gives 63%. Case 14 uses n = 10; case 17 uses 40 shards.
- Queuing and head-of-line blocking mean a few slow requests slow others behind them, so measure latency on the client side.

## 6. Capacity formulas
- Storage = rate × size × retention × replication factor. Add index overhead (case 17: inverted index 30–50% of text size).
- Bandwidth = rate × size. A fan-out multiplies it by the fan-out factor.
- Cache memory = entries × bytes per entry × overhead (case 14: about 2× for IDs plus structure).
- Servers = peak load / per-server capacity, then add headroom (case 17: set replicas by query load, not only by durability).
- Little's law: concurrent requests in flight = arrival rate × average time in system.
- Quorum: majority of n is floor(n/2) + 1. 3 nodes tolerate 1 failure, 5 tolerate 2, 4 tolerates the same as 3 (case 16).
- Hashing and partitions: partitions ≥ peak throughput / per-partition throughput, and ≥ the consumer parallelism you want later. Partition count is hard to change (cases 18, 19).
- Replay time = max(read time from the log, write time to the sink). The sink is usually the limit (case 19).

## 7. Figures from the design cases
| Figure | Value | Case |
|---|---|---|
| Posts per second (2012 anchor) | 4.6K average, 12K peak | 14 |
| Timeline reads per second | 300K (about 65× posts) | 14 |
| Average followers | about 75 (skewed, tail up to tens of millions) | 14 |
| Timeline insertions from fan-out on write | 4.6K × 75 = 345K per second, 900K at peak | 14 |
| One celebrity post, 30M followers | 30M insertions, 100 s at 300K per second | 14 |
| Timeline cache | 100M users × 800 IDs × 8 B ≈ 1.3 TB with overhead | 14 |
| Hydration load | 300K reads × 20 posts ≈ 6M post lookups per second | 14 |
| Post storage | 400M per day × 300 B ≈ 120 GB per day | 14 |
| Follower graph | 300M accounts × 75 ≈ 22B edges ≈ 700 GB | 14 |
| Ticket flash sale | 500K users in 5 min, 1.5M hold attempts, 50,000 seats | 15 |
| Hot seats | 750K attempts on 5,000 rows ≈ 150 per seat | 15 |
| Seat row | 100 B × 50,000 = 5 MB per event | 15 |
| Hold expiry | about 30 per second with 10-minute holds | 15 |
| Coordination service | 50K sessions, heartbeat 3 s, timeout 10 s ≈ 17K heartbeats per second | 16 |
| Coordination data | 1M keys × 1 KB ≈ 1 GB | 16 |
| Lock lease versus pause | 15 s lease, 20 s GC pause breaks it without fencing | 16 |
| Batch inputs | catalog 1 TB, logs 2 TB per day, 14-day window 28 TB | 17 |
| Ideal scan | 200 nodes × 200 MB/s ≈ 40 GB per second, 3 TB in about 75 s | 17 |
| Skewed key | 80M events × 250 B = 20 GB on one reducer, about 400 s | 17 |
| Index | 0.5–1 TB, 40 shards of about 25 GB, 5K queries × 40 shards = 200K shard-queries per second | 17 |
| Change stream | 3K per second average, 10K peak, 2 KB each ≈ 6–20 MB per second | 18 |
| Click stream | 200K per second × 300 B = 60 MB per second ≈ 5 TB per day | 18 |
| Join state | 30-minute window ≈ 108 GB, 24 hours ≈ 5 TB | 18 |
| Exactly-once overhead | 10–30% throughput, 100 ms to a few seconds commit interval | 18 |
| Transfers | 5K per second average, 20K peak, 500 B each ≈ 216 GB per day | 19 |
| Dedup state | 24 h window ≈ 7 GB, 7 days ≈ 48 GB, forever ≈ 2.5 TB per year | 19 |
| Replay of 7 days | read ≈ 470 s, write into a 100K per second sink ≈ 8.4 h | 19 |
| Logging every read | 100K reads per second × 200 B ≈ 1.7 TB per day, 8× the write log | 19 |
| Social feed estimation example | 300M MAU, 50% daily, 2 tweets per day → 3,500 QPS, 7,000 peak, 30 TB media per day, about 55 PB in 5 years (illustrative, not real) | Textbook-style example |

## 8. Habits for the numbers part of an interview 
- Round aggressively (99,987 / 9.1 becomes 100,000 / 10). Precision is not expected.
- Write down every assumption and label every unit.
- State what the number is used for and which design decision it changes. A number that changes nothing is noise.
- Common estimates: QPS, peak QPS, storage, cache size, number of servers, bandwidth.
- The process matters more than the result. Say it out loud.

## 9. Known limits of this sheet
- The latency table is the 2010 set with an approximate 2020 column. Real numbers vary by cloud, hardware and version.
- The rules of thumb in section 3 and the case figures in section 7 (except the 2012 Twitter numbers and the social feed example) are assumptions made while writing the cases. Quote them as estimates and benchmark when it matters.
- The weekly and monthly availability columns are computed (30.44-day month, 365.25-day year) and may differ slightly from other published tables.
