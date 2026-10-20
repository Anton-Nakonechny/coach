# 09 News feed

## Opening prompt
"Design the news feed for a social network."

## Hidden requirements
- Q: Which clients? → A: Web, iOS and Android.
- Q: Core features? → A: Publish posts (text and photos); see a feed of posts from the accounts you follow; show like and comment counts. Comment threads and video processing are out of scope.
- Q: What does the follow graph look like? → A: One-directional follows. The average user follows 200 accounts, the maximum is 5,000, and some creators have up to 50M followers. [critical]
- Q: What scale? → A: 20M daily active users, each opening the feed about 10 times a day; about 0.1 new posts per DAU per day (2M posts/day), so reads outnumber writes about 100 to 1. [critical]
- Q: What ordering? → A: Reverse chronological for version 1; relevance ranking may come later.
- Q: How fresh must the feed be? → A: A post shows up in followers' feeds within 10 s at p95 for normal accounts, and within 60 s for mega-accounts. [critical]
- Q: Latency? → A: p99 under 300 ms for the first 20 items; infinite scroll with stable pagination.
- Q: Consistency? → A: Authors see their own post immediately; followers can be eventually consistent; deleted posts disappear within 60 s; blocked users disappear within 10 s.
- Q: Availability? → A: 99.95%; serving a slightly stale feed is better than an error. [critical]
- Q: Media? → A: About 30% of posts have a photo averaging 1 MB, served through a CDN.
- Q: Geography? → A: Three regions; users read from the nearest.
- Q: Privacy? → A: Private accounts (only approved followers see posts); GDPR deletion; blocks and mutes.
- Q: Inactive users? → A: About 60% of registered users are inactive; do not waste work on them.
- Q: Notifications? → A: Push alerts for close friends are a separate system (see the notification system case).
- Q: Cost driver? → A: Memory. The feed cache holds at most 500 post IDs per active user.

## Back-of-envelope
- Feed reads: 20M x 10 = 200M loads/day / 86,400 ≈ 2,300/s average; peak 3x ≈ 7,000/s. At 20 items per load, that is about 140K post hydrations/s at peak.
- Posts: 20M x 0.1 = 2M/day ≈ 23/s average, about 70/s at peak. Read to write ratio ≈ 100:1.
- Fan-out on write: 2M posts x 200 followers = 400M feed insertions/day ≈ 4,630/s average, about 14K/s at peak. If only the 40% of followers who are active get a push, 160M/day ≈ 1,850/s. For comparison, the widely cited Twitter figures (2012): 4.6K tweets/s x 75 average followers ≈ 345K insertions/s.
- Celebrity problem: one post from an account with 50M followers = 50M insertions. At 100K insertions/s that takes 500 s (8.3 minutes); meeting a 60 s target would need about 833K insertions/s. So accounts above a follower threshold (say 100K) use fan-out on read.
- Feed cache: 500 post IDs x 16 B (8 B id + 8 B timestamp) = 8 KB per user; for 50M cache-eligible users = 400 GB raw, about 800 GB with data-structure overhead; at about 64 GB per node that is about 13 primaries, about 26 nodes with one replica copy.
- Other caches: hot posts (last three days, 6M x 1 KB) ≈ 6 GB; user profiles (100M x 500 B) ≈ 50 GB.
- Read path cost: one feed load = 1 feed-cache read + a few celebrity timeline reads + batched fetches for 20 posts, authors and counters ≈ 60 cache operations; 7,000 loads/s x 60 ≈ 420K cache ops/s at peak.
- Storage: post metadata 2M x 1 KB = 2 GB/day ≈ 730 GB/year; media 600K photos/day x 1 MB = 600 GB/day ≈ 219 TB/year.
- Fan-out workers: 14K insertions/s at peak; if one worker pipelines about 2K cache writes/s, about 7 workers, plus headroom for bursts.

## Reference design
**Core API:**
- `POST /v1/posts` body `{text, mediaIds[], visibility}`.
- `GET /v1/feed?cursor=&limit=20` returns `{items[], nextCursor}`.
- `POST /v1/follow` and `DELETE /v1/follow` (follow, unfollow).
- `DELETE /v1/posts/{id}`.

**Data model:**
- `posts(post_id, author_id, body, media, created_at, visibility)`, where `post_id` is a time-ordered 64-bit ID (see case 05).
- `follows` stored twice: by follower (to read who I follow) and by followee (to fan out), both sharded by their leading key.
- Feed cache: per-user sorted list of post IDs scored by time, capped at 500.
- Per-author recent-posts list (used for celebrity pull).
- Post, user and counter caches in front of the databases.

**Publish flow:** the post service writes the post to the database and post cache, then enqueues a fan-out job. Fan-out workers read the author's followers in pages, skip inactive followers and anyone who has blocked or muted the author, apply privacy rules, and insert the post ID into each follower's feed list (trimmed to 500). Accounts above the celebrity threshold are skipped.

**Read flow:** the feed service reads the user's cached ID list, merges in recent posts from followed celebrities (pull) and the user's own latest posts, sorts by time, fetches post and author objects in batches, filters deleted or hidden items, and returns a cursor based on the last `post_id`.

**Cold and inactive users:** if a user's feed is missing or stale, rebuild it by pulling recent posts from the accounts they follow, then cache the result.

**Key trade-offs and rejected alternatives:**
- Fan-out on write only: fast reads but huge write amplification for mega-accounts and wasted work for inactive users. Rejected as the sole approach.
- Fan-out on read only: simple writes but slow reads (each load merges hundreds of timelines); fine for celebrities and cold users.
- Hybrid (chosen): push for normal accounts, pull for celebrities and inactive users. This is the approach Twitter was described as moving to.
- Cache IDs only vs full objects: IDs keep memory small and make deletes and edits easy; the price is a hydration step on every read.
- Relational vs graph database for follows: sharded relational or key-value tables serve the two lookups (followers of X, followed by Y) at this scale; a graph database adds little for this query pattern.
- Offset pagination vs cursor pagination (chosen): offsets shift as new posts arrive; a cursor on `post_id` stays stable.

## Deep-dive topics
- Where do you set the celebrity threshold, and how do you handle accounts in the middle (10K to 1M followers)?
- What happens to freshness when the fan-out queue backs up, and how do you detect and shed load?
- How does a user see their own post immediately even if fan-out has not finished?
- How are deletions, blocks, mutes and private-account changes applied to feeds that are already built?
- How do you backfill the feed when a user follows a new account, and clean it when they unfollow?
- How do you keep pagination stable while new posts keep arriving?
- What happens when the feed cache loses a node: how do you rebuild without stampeding the database?
- How would you add relevance ranking later, and what changes in the architecture?

## Curveballs
- Users grow 100x. What changes in the cache, the fan-out workers and the graph storage?
- New Year's Eve: posting volume spikes 20x for an hour. What happens to freshness?
- Product wants a ranked "For You" feed mixing accounts you don't follow. What changes?
- One region is lost. Do you rebuild its feed caches from scratch or replicate them?
- A celebrity with 50M followers deletes a post two seconds after publishing it. What happens?

## Red flags
- Computing the feed with one big join at read time and calling it done.
- Pure fan-out on write with no plan for celebrity accounts.
- Storing full post objects in each user's feed cache.
- Using offset pagination for an infinite scroll feed.
- Forgetting deletes, blocks and privacy, so removed posts remain visible.
- Pushing to all followers including inactive users.
- Claiming consistent hashing fixes the celebrity problem.
- No answer for cold start or for a user who just followed someone.
- Common misconception: calls the cost of fan-out on write for users with many followers a hot-key problem; it is write amplification (the celebrity problem), while a hot key is one heavily read key
- Common misconception: says consistent hashing mitigates hot keys; it does not, see the consistent hashing case
- Common misconception: proposes a graph database for friend lists; large social networks generally use sharded relational or key-value stores for these lookups
- Ignores ranking, deletion and privacy propagation, pagination, read-your-writes for the author, and cold start or backfill on follow

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Join at read time, or pure push with no limits | Explains push vs pull, uses a feed cache of IDs, hydrates posts, uses a CDN for media | Does the read and fan-out math and explains why hybrid is needed |
| Senior | Ignores celebrities, deletions or freshness targets | Hybrid fan-out with thresholds, cursor pagination, skip inactive users, handles deletes and blocks | Quantifies the celebrity case, sizes the cache, designs backlog handling and cold-start rebuild |
| Staff | Treats it as a diagram | Plans for ranking, privacy and multi-region from the start | Evolves chronological to ranked feed (candidate retrieval plus ranker), controls cost per active user, and plans for traffic spikes and regional failure |
