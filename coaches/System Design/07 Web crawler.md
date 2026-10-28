# 07 Web crawler

## Opening prompt
"Design a web crawler."

## Hidden requirements
- Q: What is the crawler for? → A: Feeding a search engine index; nothing else consumes its output.
- Q: How much must it crawl? → A: About 1B page fetches per month; the known URL set is about 20B URLs. [critical]
- Q: Which content types? → A: HTML only now; images and PDFs may be added later, so the design must be extensible.
- Q: How fresh must pages be? → A: News sites hourly, the top 1% of sites daily, the median page monthly; unchanged pages should cost almost nothing to recheck. [critical]
- Q: What do we store? → A: The latest version of each page plus a snapshot every time it changes, for five years.
- Q: How are duplicates handled? → A: Exact duplicates are dropped; detecting near-duplicates is a nice-to-have.
- Q: How polite must we be? → A: Obey robots.txt (including a crawl delay if present), at most 1 request per second per host by default, and identify the crawler with a user agent and contact URL. [critical]
- Q: What about JavaScript-heavy pages? → A: About 15% of pages need rendering in a headless browser; that runs as a separate, lower-priority pipeline.
- Q: Where does it run? → A: Fetchers in three regions close to the hosts they crawl; storage in one central region.
- Q: Failure behavior? → A: Crash-safe: restarting must not lose the frontier, and losing a fetcher must not lose URLs.
- Q: Throughput target? → A: About 400 pages/s steady, 800 pages/s at peak.
- Q: Legal and compliance? → A: Honor robots.txt and noindex, never crawl behind logins, send no cookies, and handle takedown and GDPR removal requests within 30 days. [critical]
- Q: What bad content should we expect? → A: Spider traps (infinite calendars, session-ID URLs), soft 404 pages, spam farms.
- Q: Who reads the output? → A: The indexer, which wants page content, extracted links and fetch metadata through a queue.
- Q: Budget? → A: About 30 fetcher machines plus storage; no more than a few hundred machines overall.

## Back-of-envelope
- Rate: 1B / (30 x 86,400 = 2.59M s) ≈ 386 pages/s ≈ 400; peak 2x = 800.
- Storage, a common textbook assumption: 500 KB per page x 1B = 500 TB/month; x 60 months = 30 PB.
- Storage, my assumptions: average HTML 100 KB uncompressed, stored with about 5:1 compression = 20 KB; after dropping 29% duplicates, 20 KB x 0.71 = 14.2 KB per fetched page. 1B x 14.2 KB = 14.2 TB/month; x 60 = 852 TB ≈ 0.85 PB. (Assumes every fetch stores new content; rechecks of unchanged pages store nothing, so this is an upper bound.)
- Download bandwidth: pages arrive compressed at about 25 KB: 400/s x 25 KB = 10 MB/s = 80 Mbps average, 160 Mbps at peak. (With 500 KB pages: 200 MB/s = 1.6 Gbps.)
- Concurrency: if one fetch takes about 0.5 s including DNS, peak concurrent fetches = 800 x 0.5 = 400, which asynchronous I/O handles easily; 30 machines give bandwidth and failure headroom.
- URL-seen set: 20B URLs. Bloom filter at 1% false positives needs about 9.6 bits per URL = 192 Gbit ≈ 24 GB. An exact set of 64-bit fingerprints needs 20B x 8 B = 160 GB.
- Link extraction: 50 links per page x 1B pages = 50B extracted per month; if 90% are already known, 5B new URLs/month x 100 B = 500 GB/month of new URL records.
- Politeness limit: one request per second per host means a site with 100M pages takes 100M s ≈ 3.2 years; at 10 requests/s it takes about 116 days. Politeness, not machine count, caps coverage of huge hosts.
- DNS: 400 lookups/s uncached; with a 90% cache hit ratio about 40/s reach the resolver.
- Robots.txt: about 5M active hosts per day / 86,400 ≈ 58 fetches/s if each is refreshed daily.
- Rendering: 15% of 400 = 60 pages/s x about 2 CPU-seconds each = 120 cores continuously.

## Reference design
**Core API** (internal):
- `frontier.enqueue(url, priority, depth, discovered_at)` and `frontier.next(workerId) -> url batch`.
- `storage.putPage(urlHash, fetchMeta, contentRef)` and `storage.getPage(urlHash)`.
- Output to the indexer: queue messages `{url, contentRef, links[], fetchedAt, httpStatus}`.

**Data model:**
- URL record: `{url_hash, url, host, last_fetch_at, etag, last_modified, content_hash, change_rate, next_fetch_at, status}`.
- Host record: `{host, robots_cached_at, disallow_rules, crawl_delay, next_allowed_at}`.
- Page blobs in object storage keyed by content hash (compressed); URL records in a sharded key-value store.

**Components:**
- Seed manager and URL normalizer (lowercase host, remove fragments, sort query parameters, drop session IDs).
- URL filter (extensions, blocklists, maximum length and depth).
- URL-seen check (Bloom filter in front of an exact store).
- Frontier: front queues by priority, back queues one per host, with a time-ordered heap of hosts by `next_allowed_at`.
- Fetchers with DNS cache, robots cache and conditional requests (ETag, If-Modified-Since).
- Renderer pool for JavaScript pages.
- Parser and link extractor.
- Content-seen check (exact hash, plus SimHash for near-duplicates).
- Page storage; recrawl scheduler with adaptive change-rate estimates; monitoring.

**Partitioning:** shard the URL space by host hash so one node owns a host's queue, robots.txt cache, DNS cache and politeness timer. Use consistent hashing or fixed partitions so fetchers can be added or lost with little reshuffling (see case 03).

**Flow:** seeds enter the frontier; a fetcher takes a host whose delay has elapsed, checks robots.txt, resolves DNS, downloads with a timeout, then hands the page to the parser. The parser validates it and extracts links; the content-seen check discards duplicates; links are normalized, filtered, checked against URL-seen and enqueued with a priority; the page goes to storage and the indexer queue.

**Key trade-offs and rejected alternatives:**
- BFS with priorities (chosen) vs DFS: DFS can go arbitrarily deep into one site and gets trapped.
- One global FIFO queue vs front and back queues (chosen): a single FIFO ignores politeness and priority.
- Bloom filter vs exact set for URL-seen: Bloom is small, but its false positives permanently skip some new URLs and it cannot delete; use it as a fast pre-filter in front of an exact store.
- Central frontier vs per-host sharded frontier (chosen): a central one becomes a bottleneck and cannot enforce per-host timing cheaply.
- Fixed recrawl interval vs adaptive: adaptive scheduling spends fetches where pages actually change.
- Render everything vs render selectively: rendering costs about 100x a plain fetch, so only render when the plain page has no links or content.

## Deep-dive topics
- How exactly do the front queues and back queues interact, and how do you guarantee at most one request per second per host across many fetchers?
- How do you keep the frontier crash-safe without writing every URL to disk synchronously?
- How do you avoid spider traps, and how do you detect one after the crawler has already entered it?
- How do you decide when to recrawl a page, and how do conditional requests reduce cost?
- How do you detect near-duplicate pages, and what does exact hashing miss?
- How do you normalize URLs so `a.com/x`, `A.com/x#top` and `a.com/x?utm=1` count once?
- How do you add 10 fetchers without breaking per-host politeness or losing in-flight URLs?
- How do you handle a host that is slow, returns errors, or blocks you?

## Curveballs
- The crawl target grows 10x to 10B pages per month. What changes in the frontier, URL-seen set and storage?
- A large site sends a legal complaint and demands you stop; how do you honor it quickly and prove it?
- Half the web now needs JavaScript rendering. How does the architecture change and what does it cost?
- A fetcher region goes offline for six hours. What happens to politeness state and the frontier?
- Product wants breaking-news pages indexed within 60 seconds. What changes?

## Red flags
- A single FIFO queue and "run many threads".
- No politeness: no robots.txt, no per-host rate, no user agent.
- Ignoring URL normalization.
- Treating the URL-seen set as a trivial in-memory set at 20B URLs.
- No answer for spider traps or infinite URLs.
- Hashing whole pages and calling it duplicate detection without noting near-duplicates.
- Re-downloading everything on every crawl instead of using conditional requests and change estimates.
- Skipping the legal and compliance angle entirely.
- Common misconception: stores 500 KB per page for five years (30 PB); real HTML is far smaller, stored compressed and heavily duplicated, so realistic storage is under 1 PB
- Common misconception: calls rendering JavaScript in a headless browser server-side rendering; server-side rendering is the site's job, and dynamic rendering is discouraged by search engines
- Proposes a Bloom filter for URL-seen without noting that false positives permanently skip some new URLs
- Common misconception: treats `Crawl-delay` as part of the robots.txt standard (RFC 9309); it is not, and some crawlers ignore it
- Skips URL normalization, near-duplicate detection (SimHash or shingling), conditional requests and recrawl scheduling

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Describes fetch, parse, queue loop only | Adds frontier, robots.txt, URL-seen, content-seen, politeness, and does basic QPS and storage math | Explains BFS with priorities, host-based queues, and why exact-set URL-seen is hard at scale |
| Senior | Single global frontier and no failure story | Shards by host, handles DNS, traps, recrawl, crash recovery, and conditional fetch | Quantifies politeness limits per host, sizes Bloom filter and exact store, and designs adaptive recrawl and rendering budget |
| Staff | Lists components of Mercator without trade-offs | Frames crawling as a budget (fetches, bandwidth, goodwill) allocated by value | Designs for legal, operational and cost constraints, evolves the system for 10x, and challenges requirements such as 5-year raw storage |
