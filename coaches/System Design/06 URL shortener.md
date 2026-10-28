# 06 URL shortener

## Opening prompt
"Design a URL shortening service like TinyURL."

## Hidden requirements
- Q: What are the core functions? → A: Create a short link for a long URL, and redirect a short link to its long URL. Paid users can pick a custom alias. Owners cannot edit the destination; the abuse team can disable a link.
- Q: How many links are created? → A: 100M new links per day. [critical]
- Q: How many redirects? → A: About 100 redirects per link over its life (100:1 read to write), heavily skewed: the top 1% of links draw about 80% of clicks. [critical]
- Q: How long do links live? → A: Ten years by default; expired or disabled links return 410 Gone.
- Q: How long may a short code be? → A: As short as possible, using 0-9, a-z and A-Z.
- Q: Redirect latency? → A: Server-side p50 under 10 ms and p99 under 50 ms, for users worldwide. [critical]
- Q: Availability? → A: 99.99% for redirects; 99.9% for link creation.
- Q: Consistency? → A: A new link must resolve everywhere within 1 second; the creator sees it immediately.
- Q: Analytics? → A: Clicks per link per day and per country, up to an hour late, and it must never slow the redirect. Every click must reach our servers, so browsers must not cache redirects permanently. [critical]
- Q: Geography? → A: Global users; three regions.
- Q: Abuse and security? → A: Check destinations against a malware list at creation and periodically; rate limit creators; short codes must not be easy to enumerate. [critical]
- Q: Deduplication? → A: The same user submitting the same long URL gets the same code; different users get different codes so their analytics stay separate.
- Q: Compliance? → A: GDPR: anonymize IP addresses in click logs after 30 days; a deletion request must take effect, including in caches, within 24 hours.
- Q: Budget? → A: Moderate; most redirects should be served from cache or the edge.

## Back-of-envelope
- Writes: 100M/day / 86,400 ≈ 1,160/s average; peak (3x) ≈ 3,500/s.
- Reads: 100:1 means 10B redirects/day / 86,400 ≈ 116K/s average; peak ≈ 350K/s.
- Records: 100M x 365 x 10 = 365B links over ten years.
- Code length: smallest n with 62^n >= 365B. 62^6 = 56.8B is too small; 62^7 = 3.52 trillion fits, so 7 characters (utilization about 10%).
- Row size: short code 7 B + long URL about 100 B + created/expiry timestamps 16 B + owner id 8 B + flags and overhead about 20 B ≈ 150 B. Storage: 365B x 150 B ≈ 55 TB; with 3 replicas ≈ 165 TB. (If only the 100 B URL is counted, 365B x 100 B = 36.5 TB.)
- Shards: at about 2 TB per node, about 28, so plan 30 shards. Writes at 3,500/s are trivial per shard; the constraint is data size.
- Cache: keep about 200M of the hottest links x 150 B ≈ 30 GB; because of skew, 90–95% hit ratio is realistic. Database reads at peak: 5% of 350K ≈ 17.5K/s.
- Redirect bandwidth: about 500 B per response x 350K/s ≈ 175 MB/s ≈ 1.4 Gbps at peak.
- Redirect servers: about 20K requests/s each when serving from cache; 350K / 20K ≈ 18, plan about 30 for headroom.
- Analytics events: 116K clicks/s x about 200 B ≈ 23 MB/s ≈ 2 TB/day raw; aggregate hourly.
- ID blocks: 20 app servers each take blocks of 1M IDs; each server consumes about 58 IDs/s, so a block lasts about 4.8 hours.

## Reference design
**Core API:**
- `POST /v1/links` body `{longUrl, alias?, expiresAt?}` returns `{shortUrl}`.
- `GET /{code}` returns a 302 redirect with a `Location` header (410 if expired or disabled, 404 if unknown).
- `DELETE /v1/links/{code}` (owner or abuse team).
- `GET /v1/links/{code}/stats` (aggregated analytics).

**Data model:**
- `links(code PK, long_url, owner_id, created_at, expires_at, status)`.
- A dedupe index keyed on `(owner_id, hash(long_url))` rather than the full URL string.
- Click events go to a queue and then to aggregated tables `(code, day, country, clicks)`.
- Sharded by `code` (hashed) so redirects hit one shard.

**Code generation:** a counter service hands each app server a block of numeric IDs (say 1,000,000 at a time); the server converts each ID to base 62. To stop enumeration, pass the ID through a reversible shuffle (for example a small Feistel permutation) before encoding, so codes look random but remain unique and collision-free.

**Redirect flow:** CDN or edge cache, then redirect service with an in-memory or Redis cache, then database on a miss. Return 302 so every click is observed; emit a click event asynchronously.

**Components:** load balancer, redirect service (read path), creation service (write path), counter service, cache tier, sharded database with replicas, message queue and stream aggregator for analytics, safe-browsing checker, rate limiter on creation.

**Key trade-offs and rejected alternatives:**
- Base 62 of a unique ID (chosen) vs hashing the long URL (MD5, SHA-1, CRC32) and truncating: truncation needs collision checks against the database on every write (Bloom filters reduce this but do not remove it), while counter-based IDs never collide.
- 302 vs 301: 301 lets browsers cache and bypass the service forever, which kills analytics and makes disabling a link impossible for those users; 302 costs more traffic, which the cache absorbs. Use 301 only if analytics and revocation are unimportant.
- Single counter vs block allocation: block allocation removes the per-request call and tolerates counter-service outages for hours; the price is gaps in the sequence, which do not matter.
- Relational vs key-value database: lookups are by primary key, so a sharded key-value or relational store both work; choose the one the team can operate.
- Deduplicating by long URL for everyone vs per user: global dedupe saves space but mixes analytics and ownership; per-user dedupe is chosen.
- Synchronous vs asynchronous analytics: asynchronous so redirect latency is unaffected.

## Deep-dive topics
- How do you generate codes that are short, unique and hard to guess without a database lookup on every write?
- Walk through the redirect path for a link that is not in the cache, and for a link that is not in the database.
- How do you serve a link that is suddenly going viral at 100K requests/s?
- How do you take down a malicious link globally within minutes when browsers, CDNs and caches may hold it?
- How is analytics collected without slowing redirects, and how do you handle duplicates and late events?
- How do you pick a shard key and rebalance as the table grows past 50 TB?
- How do you expire 10-year-old links, and how do you reclaim and avoid reusing codes?
- What happens during a regional failure: which operations continue and which degrade?

## Curveballs
- Traffic grows 100x. What changes in the cache, edge and database layers?
- Marketing launches a campaign; one link gets 1M requests/s for ten minutes.
- Legal requires deleting a link everywhere within 15 minutes. What do you change?
- Product wants custom branded domains per customer. What changes in routing and data model?
- A bot creates 50M links in an hour pointing to phishing pages. How does the system react?

## Red flags
- Computing storage by multiplying by the retention years twice.
- Truncating a hash and ignoring collisions.
- Choosing 301 without noticing it defeats analytics and revocation.
- Putting analytics writes on the synchronous redirect path.
- Sequential, guessable short codes with no mention of enumeration.
- Using 64-bit time-based IDs and base-62 encoding but still claiming 7 characters.
- No abuse, expiry or deletion story.
- Treating the read path and write path as the same scaling problem.
- Common misconception: computes 365 billion x 100 bytes x 10 years = 365 TB, multiplying by years twice; 365 billion x 100 B is 36.5 TB
- Common misconception: feeds 64-bit snowflake IDs into base-62 and still claims 7 characters; 7 characters hold only numbers below about 3.52 trillion, while 64-bit values need 10 to 11
- Common misconception: assumes a 10:1 read-to-write ratio; shorteners are usually far more read-heavy
- Common misconception: redirects with 301 by default; browsers cache it permanently, breaking analytics and takedown, so 302 is the safer default
- Stores both the numeric id and the short code in the table although the code can be derived from the id, or checks whether the long URL already exists without noting that indexing long strings is costly

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Hash table in memory; no storage numbers | Relational table, base-62 or hash approach with collision handling, cache for reads, correct 7-character math | Correct QPS and storage math, explains 301 vs 302 and the unique-ID source |
| Senior | Ignores expiry, abuse, analytics | Separates read and write paths, sharding, cache sizing, async analytics, rate limiting | Handles viral links and revocation across caches, prevents enumeration, and quantifies cache hit ratio and database load |
| Staff | Treats it as a CRUD service | Frames the service around read-heavy skew, abuse and compliance; chooses managed pieces deliberately | Designs for global edge serving, safe takedown at scale, retention and cost per million redirects, and challenges requirements that add complexity |
