# 02 Rate limiter

## Opening prompt
"Design a rate limiter for our public API."

## Hidden requirements
- Q: Client-side or server-side limiter? → A: Server-side, enforced in front of the API.
- Q: What identifies a caller? → A: API key for authenticated traffic, IP address for anonymous traffic; rules differ per endpoint and per pricing tier. [critical]
- Q: What scale? → A: About 100K requests/s on average, 300K at peak, across roughly 300 API servers; 5M active API keys; 50M distinct IPs per day. [critical]
- Q: Latency budget? → A: The limiter may add at most 2 ms at p99. [critical]
- Q: How accurate must limits be? → A: Over-admitting by 5–10% is fine for general limits; strict (no overshoot) for costly endpoints such as "send SMS", limited to 5 per day per account.
- Q: Are bursts allowed? → A: Yes; paid tiers may burst to about 2x their rate for up to a second.
- Q: What if the limiter's data store is down? → A: Fail open for read endpoints; fail closed for the SMS and payment endpoints. [critical]
- Q: Geography? → A: Three regions (US, EU, APAC); a customer's quota should be global in principle, but per-region enforcement with up to 10% drift is acceptable.
- Q: How are rules changed? → A: By operations without a redeploy; changes must take effect within 30 s.
- Q: What do throttled clients see? → A: HTTP 429 with a retry hint and remaining-quota information.
- Q: Should throttled requests be queued? → A: No, reject them; only the batch-import endpoint may enqueue.
- Q: Where should it live? → A: Your call; today an API gateway fronts all traffic.
- Q: Privacy? → A: IPs count as personal data under GDPR, so counters keyed by IP must expire within 24 hours.
- Q: Budget? → A: A shared Redis cluster is fine; the limiter must cost under 1% of API infrastructure.
- Q: Is DDoS protection in scope? → A: No; a WAF handles volumetric attacks, but a coarse per-IP limit at the edge is expected.

## Back-of-envelope
- Checks: every request needs one limiter check, so 300K checks/s at peak. If each check is one atomic Redis operation and one Redis node handles about 100K simple operations/s (assumption), that is at least 3 primary shards; use 6 for headroom, each with a replica = 12 nodes.
- Memory (token bucket): per entry store tokens (8 B) + timestamp (8 B) + key (about 40 B) + store overhead (about 100 B) ≈ 150 B. Keys: 5M API keys x 4 rules (global plus 3 endpoint groups) = 20M entries, plus 50M IP entries = 70M x 150 B ≈ 10.5 GB total, under 2 GB per shard across 6 shards.
- Memory (sliding window log, for comparison): a key allowed 1,000 requests/minute stores up to 1,000 timestamps at about 50 B each = 50 KB per key; 5M keys x 50 KB = 250 GB. That is why the log algorithm is rejected for the general case.
- Network: 300K checks/s x about 150 B = 45 MB/s, negligible.
- Latency: a same-availability-zone Redis round trip is about 0.5 ms, so one check fits the 2 ms budget; two sequential round trips would not leave room for retries.

## Reference design
**Core API** (internal, called by gateway middleware):
- `check(clientId, resource, cost=1) -> {allowed, limit, remaining, retryAfterSeconds}`
- Admin API: create, read, update and delete rules.
- Client-visible: HTTP 429 plus `Retry-After` and rate-limit headers.

**Data model:**
- Rule: `{domain, descriptor (e.g. endpoint group + tier), unit, requests_per_unit, burst, algorithm, mode: hard|soft, fail_mode: open|closed}`. Stored in a config database and pushed to gateways' local caches.
- Counter state in Redis: key `rl:{ruleId}:{clientId}` holds a hash `{tokens, last_refill_ts}` with a TTL slightly longer than the refill period.

**Components:** gateway middleware, local rule cache, Redis cluster (sharded by key), rule-sync worker, metrics and alerting, dashboard for throttle rates.

**Flow:** request arrives, middleware finds the matching rules in its local cache, runs one atomic Lua script on the shard that owns the key (refill by elapsed time, take a token if available, return remaining), then forwards or returns 429.

**Key trade-offs and rejected alternatives:**
- Token bucket (chosen) vs leaking bucket: token bucket allows bursts and uses two numbers per key. The leaking bucket smooths output but delays recent requests behind old ones.
- Token bucket vs fixed window: fixed windows let a client send double the limit across a boundary (5 requests at 2:00:59 plus 5 at 2:01:01).
- Sliding window log: exact but costs memory proportional to the limit (250 GB above). Use only for small, strict limits.
- Sliding window counter: cheap approximation that smooths boundary bursts; good for per-minute public limits.
- Central Redis vs local in-memory counters per gateway: local counters are fastest but each gateway sees only part of a client's traffic, so limits drift by the fleet size; chosen design is central Redis with optional local token leasing for hot keys.
- Sticky routing to a limiter instance: rejected; it breaks on scale events and failures.
- Locks around read-modify-write: rejected; they add latency and contention. Use one atomic script instead.
- Gateway placement vs inside each service: gateway chosen because it already exists and gives one consistent enforcement point.

## Deep-dive topics
- Race conditions: how do you make "read counter, compare, write" atomic across hundreds of servers without locks?
- Clock skew: whose clock is used for refill and window boundaries, and what goes wrong if gateway clocks disagree?
- Hot key: one enterprise customer sends 50K requests/s to a single Redis key. What breaks and how do you split the load?
- What exactly happens when a Redis shard dies? What do the fail-open and fail-closed rules mean per endpoint?
- Multi-region: how do you enforce one global quota without a cross-region call on every request?
- How do you roll out a bad rule safely and detect that valid traffic is being rejected?
- How should clients behave after a 429, and how do you prevent a synchronized retry stampede?
- How would you rate limit by cost (an export counts as 50 units) rather than request count?

## Curveballs
- Traffic grows 100x. What is the first component to saturate and how do you scale it?
- The Redis cluster in one region goes down during a sale. What do users see?
- A single IP behind a corporate NAT is blocked while 5,000 legitimate users share it. What do you change?
- A botnet sends one request each from 1M IPs. Does per-IP limiting help? What does?
- Product wants one global quota across three regions, accurate to 1%. What does that cost in latency?

## Red flags
- Enforcing limits only on the client side.
- Storing counters in a disk-based database.
- Read-then-write counter updates with no atomicity (race condition).
- Using sticky sessions or one global lock to synchronize limiter servers.
- Not stating what happens when the limiter itself fails.
- Choosing an algorithm without discussing burst behavior and memory.
- Returning a bare error with no retry information.
- Using application server wall clocks for window boundaries without noting skew.
- Common misconception: says locks, Lua scripts or sorted sets all solve the counter race equally; a fixed-window counter needs only an atomic increment with expiry, a token bucket needs a Lua script or transaction, and sorted sets alone are not atomic across several commands
- Common misconception: invents a custom retry header instead of the standard `Retry-After` (RFC 9110) plus `RateLimit` / `X-RateLimit-*` quota headers

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Names token bucket but can't explain refill or parameters; no storage choice | Compares two or three algorithms with pros and cons; uses Redis with atomic operations; returns 429 with a retry hint | Computes memory and QPS per shard, explains the fixed-window boundary flaw with numbers, and handles limiter failure explicitly |
| Senior | Ignores races, clock skew or limiter failure | Covers atomicity, rule distribution, fail-open vs fail-closed per endpoint, and multi-region drift | Identifies hot keys and proposes local token leasing or key splitting; designs rule rollout and monitoring for false rejections |
| Staff | Treats it as one box with one algorithm | Frames limiting as a product with tiers, cost-based limits and abuse tiers; ties accuracy to cost | Chooses different mechanisms per layer (edge, gateway, service), quantifies the accuracy-versus-latency trade for global quotas, and plans for overload shedding beyond per-client limits |
