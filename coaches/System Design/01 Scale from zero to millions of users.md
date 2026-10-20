# 01 Scale from zero to millions of users

## Opening prompt
"We're launching a consumer app with a website and mobile apps. Design the backend so it works for our first hundred users and keeps working as we grow into the millions."

## Hidden requirements
- Q: What does the product do? → A: Users sign up, post short text and photos, follow other users and read a feed of followed users' posts.
- Q: Which clients? → A: Web, iOS and Android, all through one JSON-over-HTTPS API.
- Q: How big must it get? → A: 10M registered users and 2M daily active users (DAU) within 18 months; under 1,000 users at launch. [critical]
- Q: What is the read/write ratio? → A: About 50 reads per write by request count (50 requests per DAU per day, 2% of them writes). [critical]
- Q: Latency target? → A: p99 under 500 ms for API reads; pages usable in 2 s; images served from a CDN.
- Q: Consistency needs? → A: Authors must see their own post immediately; other users seeing it a few seconds late is fine. [critical]
- Q: Availability target? → A: 99.95% monthly (about 22 minutes of downtime a month); no maintenance windows after launch. [critical]
- Q: Durability? → A: Losing a cache is fine; losing a user's post or photo is not. [critical]
- Q: Geography? → A: US and Western Europe first; a second region is likely within a year.
- Q: Media volume? → A: 0.5 posts per DAU per day; 20% of posts carry a photo of about 1 MB after server-side resizing; users view about 30 photo thumbnails (200 KB each) per day.
- Q: Traffic shape? → A: Evening peak is 3x the daily average; a marketing launch can spike 10x the average for an hour.
- Q: Compliance? → A: GDPR applies: EU users can request deletion; no payment data is stored (a payment provider handles it).
- Q: Team and budget? → A: Six engineers, cloud-hosted, managed services preferred; cost matters more than architectural purity.
- Q: Existing tech? → A: Greenfield; the team knows Java and PostgreSQL.

## Back-of-envelope
- Requests: 2M DAU x 50 = 100M requests/day. Average QPS = 100M / 86,400 ≈ 1,200. Peak (3x) ≈ 3,500. Launch spike (10x average) ≈ 12,000.
- Reads vs writes: writes are 2% = 2M/day ≈ 23 QPS average (about 70 peak). Reads ≈ 1,130 QPS average (about 3,400 peak).
- Post metadata: 1M posts/day x 1 KB = 1 GB/day. Over 5 years: 1 GB x 365 x 5 ≈ 1.8 TB.
- Photos: 200K photos/day x 1 MB = 200 GB/day ≈ 73 TB/year ≈ 365 TB over 5 years. With 3x replication in object storage, about 1.1 PB raw (the provider handles it).
- Image egress: 2M x 30 views x 200 KB = 12 TB/day ≈ 139 MB/s ≈ 1.1 Gbps average; peak 3x ≈ 3.3 Gbps. With a 90% CDN hit ratio the origin sees about 0.33 Gbps at peak.
- Cache: 2M DAU x 5 KB of hot profile and feed data ≈ 10 GB (one node's memory, but run two or three for availability).
- Database load: 3,400 peak reads with an 80% cache hit ratio leaves about 680 reads/s on the database, plus about 70 writes/s. A single primary with replicas handles this; sharding is not needed yet.
- Web tier: assume 500 requests/s per instance. Peak 3,500 needs 7 instances; with 2x headroom across 3 availability zones, about 14. A 12,000 QPS spike needs about 24, so autoscale between 6 and 25.

## Reference design
**Core API** (all over HTTPS, bearer token):
- `POST /v1/posts` (text plus optional media id)
- `GET /v1/feed?cursor=` (cursor pagination, not offset)
- `GET /v1/users/{id}`
- `POST /v1/users/{id}/follow`
- `POST /v1/media/upload-url` (returns a pre-signed object-storage URL so the client uploads photos directly)

**Data model** (relational to start):
- `users(id, handle, created_at, ...)`
- `posts(id, author_id, body, media_id, created_at)`, indexed on (author_id, created_at)
- `follows(follower_id, followee_id)`
- `media(id, owner_id, object_key, variants)`
- Sessions live in a shared store, not on web servers.

**Evolution, in the order to narrate it:**
1. Everything on one server: web app, database and cache.
2. Split web tier and data tier so they scale separately.
3. Add a load balancer and make the web tier stateless (sessions in Redis or the database), then autoscale.
4. Add a database primary with read replicas (writes to the primary, reads to replicas) for failover and read capacity.
5. Add a cache for hot reads and a CDN for static files and photos.
6. Add a message queue and workers for slow jobs (photo resizing, notifications).
7. Go multi-AZ first, then multi-region with geo-routed DNS and cross-region replication.
8. Shard the database only when write volume or data size demands it; choose a shard key that spreads load evenly.
9. Add logging, metrics, alerting and automated deploys throughout.

**Key trade-offs and rejected alternatives:**
- Relational vs NoSQL: choose PostgreSQL because the data is relational, the team knows it, and 70 writes/s is tiny. Reject NoSQL-first as premature.
- Sticky sessions vs stateless web tier: choose stateless. Sticky sessions make scale-in, failover and deploys harder.
- Early sharding vs vertical scaling plus replicas: choose the latter. Sharding brings resharding pain, hot shards and no cross-shard joins, and is not needed at these numbers.
- Synchronous vs queued photo processing: choose queued. It keeps request latency low and lets workers scale on queue depth.
- Active-passive vs active-active regions: start active-passive for simplicity; move to active-active only when latency or availability demands it, because it forces a conflict-resolution story.
- Cache pattern: choose cache-aside with a TTL. Accept brief staleness; invalidate on writes for hot keys.

## Deep-dive topics
- Walk through exactly what happens when the primary database fails: detection, promotion of a replica, lost writes from async replication, and how clients reconnect.
- How do you keep "read your own writes" correct while reads go to lagging replicas?
- Cache invalidation: how do you avoid stale profiles after an edit, and what happens when a hot key expires and thousands of requests hit the database at once?
- How would you choose a shard key for `posts`, and what breaks when one user (a celebrity) dominates a shard?
- How does the CDN decide when to refetch an image, and how do you replace an image that is already cached?
- What would you monitor, and what would page you at 3 a.m.?
- How do you deploy a new version without downtime across the load-balanced fleet?

## Curveballs
- Traffic grows 100x in two months. What breaks first, and in what order do you fix it?
- The primary region goes down entirely. What is the recovery time and what data can be lost?
- Product adds video uploads (50 MB average). What changes in storage, upload path and cost?
- A viral post makes one object get 40% of read traffic. What do you do?
- Legal says EU users' data must stay in the EU. How does the design change?

## Red flags
- Jumping straight to microservices, Kafka and sharding without asking for scale.
- Never asking about read/write ratio, consistency or availability targets.
- Keeping user sessions on individual web servers.
- Putting photos in the relational database.
- Treating the database as a single point of failure with no replication or backups.
- Adding a cache with no expiry, invalidation or eviction story.
- Ignoring monitoring, deploys and failure handling.
- Saying "just use auto-scaling" for the data tier.
- Common misconception: calls check-cache-then-query-DB-then-fill a read-through cache; it is cache-aside (the application loads), whereas read-through has the cache load missing data itself
- Common misconception: describes a message queue as durable because it is held in memory; production queues persist to disk or replicated storage, and memory-only queues lose messages on restart
- Common misconception: sizes an ID as 64 bytes; a typical distributed ID is 64 bits (8 bytes)

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Lists components with no reasons; one box per buzzword | Builds the tier-by-tier evolution correctly and explains why each step; does basic QPS and storage math | Does the math unprompted, shows which numbers drive each decision, and says what he would not build yet |
| Senior | Fixes bottlenecks by adding hardware; misses consistency and failover details | Handles replication lag, cache invalidation, queues and CDN with trade-offs; defines SLOs | Ties every component to a requirement, quantifies failure impact, and plans the order of scaling steps with triggers |
| Staff | Treats the exercise as a diagram | Frames scaling as stages with cost and team-size constraints; raises multi-region and data-residency early | Proposes metrics-driven triggers for each stage, designs for operability and incident response, and challenges requirements that don't justify the complexity |
