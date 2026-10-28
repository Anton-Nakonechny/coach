# 14 Twitter home timeline

## Opening prompt
"Design the home timeline of a microblogging service. People post short messages and follow other people. When a user opens the app, show them the latest messages from everyone they follow. Start by telling me how you would describe the load."

## Hidden requirements
- Q: Which operations are in scope? → A: Post a message, and read the home timeline (messages from followed accounts). Follow and unfollow are in scope as they affect the timeline. Search, trends, direct messages and ads are out of scope. [critical]
- Q: What is the load, expressed as parameters? → A: Use the published late-2012 figures as an anchor: about 4.6K posts per second on average (12K+ at peak) and about 300K timeline reads per second. Reads outnumber posts by roughly 65 to 1. A strong candidate asks for the follower distribution too, since fan-out depends on it. [critical]
- Q: What is the follower distribution? → A: Average of about 75 followers per account, but very skewed: a small number of accounts have tens of millions of followers. The mean is a trap and the tail drives design. [critical]
- Q: What freshness is expected? → A: A new post should reach followers' timelines within about 5 seconds for normal accounts. Celebrity posts may take longer to reach every follower but must show up on read.
- Q: What is the read latency target? → A: Timeline fetch p99 well under a few hundred milliseconds. The candidate should talk about percentiles and not averages, and about tail latency when one request touches many backends.
- Q: How is the timeline ordered? → A: Reverse chronological. A ranked timeline is a follow-up that changes where the work happens.
- Q: How deep must the timeline go? → A: The first page (about 20–50 items) must be very fast. Scrolling back a few hundred items is common. Beyond that (thousands) can be slower or come from a different path.
- Q: What happens when someone deletes a post, blocks or mutes someone, or unfollows? → A: Deleted posts must disappear from timelines promptly. Unfollow, mute and block must stop content from showing. The candidate must say how this works with precomputed timelines.
- Q: Are there accounts that follow thousands of others or are followed by almost nobody? → A: Yes. Both ends exist, and heavy followers make the read-time merge expensive while heavy followees make the write-time fan-out expensive.
- Q: How consistent must the timeline be? → A: Eventual consistency is fine for fan-out delivery. A user must see their own post immediately (read-your-writes), and must not see the timeline go backwards on refresh (monotonic reads).
- Q: How do inactive users affect cost? → A: Many accounts are dormant. Precomputing and storing timelines for them wastes memory and write capacity.
- Q: What are replies and reposts? → A: A repost appears as a new item pointing at the original. Replies are shown only under some rules. Keep it simple, but note they add to the fan-out.
- Q: Is the system available in several regions? → A: Single region first. Multi-region replication and follower graph locality are a follow-up.

## Back-of-envelope
- Posts: 4.6K per second average, 12K per second at peak. On their own, these writes are easy for a sharded store.
- Timeline reads: 300K per second. Each read is a first page of about 20–50 posts, plus the data needed to render them.
- Fan-out on write: 4.6K posts per second × 75 average followers = 345K timeline insertions per second (the widely cited 2012 figure). At peak, 12K × 75 = 900K per second. This is the real write load and it is more than 70× the posting rate.
- The celebrity case: a single post by an account with 30M followers means 30M insertions. At an assumed 300K insertions per second dedicated to it, that one post needs 100 seconds, far beyond a 5-second target, and it competes with everyone else's traffic. This is the case the hybrid approach exists for.
- Timeline cache size: assume 100M active users, 800 post IDs per timeline, 8 bytes per ID is 6.4 KB raw, about 13 KB with overhead. 100M × 13 KB is about 1.3 TB, which fits a cache cluster of a few dozen nodes with replication. Storing IDs only (not full posts) is what makes this affordable.
- Hydration: every timeline read must turn IDs into posts, authors and counts. 300K reads per second × 20 posts is about 6M post lookups per second. This is a bigger load than the timeline lookups themselves and needs a hot post cache with batched multi-get. Cache hit rates above 95% are needed to protect the stores.
- Tail latency: if one timeline read fans out to 10 backend calls and each has a 1% chance of being slow, about 10% of reads hit at least one slow call (1 − 0.99^10). This is why p99 per backend is the budget, not the mean.
- Post storage: 4.6K per second × 86,400 is about 400M posts per day. At about 300 bytes with metadata, that is about 120 GB per day, about 44 TB per year before replication.
- Follower graph: if there are 300M accounts and an average of 75 follows each, about 22B edges. At about 16 bytes per edge in two directions, about 700 GB. It must be queryable both by follower and by followee.

## Reference design
**Core API.**
- POST /posts (author, text, media refs, reply_to or repost_of) returns post_id.
- GET /timeline/home?cursor=<post_id>&limit=N returns post objects, newest first, with a next cursor. Cursor-based, not offset-based.
- POST /follow/{user}, DELETE /follow/{user}, plus mute and block endpoints.
- DELETE /posts/{id}.

**Data model.**
- Posts table: (post_id, author_id, text, created_at, ...) keyed by post_id and indexed by author_id and time. Time-ordered post IDs (snowflake style, see case 05) give a cheap chronological sort and a natural cursor.
- Follow graph: two views, followers_of(user) for fan-out and followees_of(user) for read-time merge. Typically a sharded store keyed by user, with both directions written.
- Home timeline cache: per user, a capped list of post IDs (about 800), newest first, in memory. It is a cache, not the source of truth, and can be rebuilt from posts and the graph.
- Celebrity set: a flag or follower-count threshold on the author record.
- Tombstones for deleted posts and per-user mute and block lists, applied at read time.

**Components.**
- Post service writes the post durably, then emits a post-created event to a log or queue.
- Fan-out workers consume events, look up followers, and push the post ID to each follower's timeline cache. Workers are sharded and batch their writes. They skip inactive followers (those not seen in N days).
- Timeline service on read: fetch the user's cached ID list, merge in recent posts from any celebrities the user follows (a small read-time query), apply mute, block and tombstone filters, hydrate IDs through the post cache, and return the page.
- Post cache and user cache for hydration, with batched multi-get.
- Rebuild path: when a timeline cache entry is missing (new user, eviction, dormant user returning), build it on demand from the followee list and recent posts of each followee (approach 1 from the book), then cache it.
- Backfill on follow: add recent posts of the new followee to the timeline. Unfollow removes or filters them.

**Key trade-offs and rejected alternatives.**
- Fan-out on read (query posts of everyone followed, merge on request): simple and no wasted writes, but read cost is proportional to the number of followees at 300K reads per second. The original Twitter did this and could not keep up.
- Fan-out on write (precompute each timeline): reads become one cache lookup. The cost is amplification of writes by the follower count, and wasted work for dormant users.
- Hybrid (the commonly described end state): fan-out on write for normal accounts, fan-out on read for celebrities, merged at request time. More complex, but it bounds both write amplification and read cost. The threshold is a tunable, and some systems also make it adaptive.
- Store IDs, not post bodies, in timelines: smaller, and edits and deletions need no timeline rewrites, at the price of a hydration step.
- Filtering deletions, mutes and blocks at read time rather than rewriting timelines: cheap and immediate, at the price of fetching a few extra items per page.
- Only materialize timelines for active users: saves memory and write capacity, at the price of a slower first load after a long absence.
- Pull-based fan-out through a queue absorbs bursts. The alternative of inline fan-out in the post request makes posting latency depend on follower count, which is unacceptable.

## Deep-dive topics
- Which load parameters do you pick for this system, and why is the average follower count misleading?
- Where exactly do you draw the line between fan-out on write and on read, and how do you choose and change the threshold?
- How does the read path merge the cached timeline with celebrity posts and keep the page order stable across refreshes?
- How do you make deletes, unfollows, mutes and blocks take effect immediately when timelines are precomputed?
- How do you rebuild a timeline that is missing from the cache without stampeding the database?
- How do you keep the fan-out lag under 5 seconds during a traffic spike, and what do you monitor?
- How do you give users read-your-writes and monotonic reads on an eventually consistent timeline?
- What would change if the timeline were ranked instead of chronological?

## Curveballs
- A user with 40M followers posts during a global event. Ask what happens to the fan-out queues and to every other user's delivery latency, and how isolation of heavy accounts helps.
- The cache cluster loses a third of its nodes. Ask what the user sees, how timelines are rebuilt, and how to avoid a thundering herd on the database.
- An account is hacked and posts spam to 10M followers, then the owner wants it gone. Ask how you retract it from precomputed timelines quickly.
- Product wants "show me posts I missed since I was last here" with ranking. Ask what the data pipeline looks like and where ranking runs.
- The interviewer says the follower distribution has changed: the average is now 500 and the top accounts have 200M. Ask which parts of the design you would revisit first.

## Red flags
- Computing the timeline with a join across posts and follows on every read at this scale.
- Fan-out on write for everyone, including celebrities, with no mention of the 30M-follower case.
- Quoting average QPS only, ignoring peaks and the fan-out multiplier.
- Doing fan-out inside the posting request.
- Storing full post bodies in every timeline.
- Ignoring deletion and unfollow semantics.
- Using offset pagination on a list that changes while the user scrolls.
- Speaking about average latency and never about p99 or tail amplification.
- Common misconception: presents the 2012 timeline numbers or the hybrid fan-out as the service's current internals; they are a reasoning exercise in load parameters
- Ignores deletion, mute and block semantics on precomputed timelines, and read-your-writes for the author

## Rubric signals
- Mid, weak: proposes SQL joins for reads or a single cache with no discussion of read versus write amplification.
- Mid, hire: explains fan-out on read versus write with numbers, picks fan-out on write for the read-heavy workload, and uses a timeline cache of IDs.
- Mid, strong-hire: spots the celebrity problem unprompted, proposes the hybrid, and sizes timeline cache and fan-out writes per second.
- Senior, weak: describes the hybrid but cannot give thresholds, handle deletes or explain failure of the cache tier.
- Senior, hire: hydrates IDs through a hot cache with batching, filters deletes and mutes at read time, skips dormant users, and designs cursor pagination.
- Senior, strong-hire: reasons about tail latency amplification and per-backend p99 budgets, isolates heavy fan-out work, and designs the rebuild path and stampede protection.
- Staff, weak: treats the problem as a cache design and does not tie choices to load parameters or cost.
- Staff, hire: frames the architecture as a function of distribution (followers per user weighted by post rate), plans adaptive thresholds and capacity, and covers multi-region.
- Staff, strong-hire: can evolve the system to ranked timelines, explains the cost and freshness trade-offs between precomputation and request-time ranking, and defines SLOs and error budgets for delivery lag.
