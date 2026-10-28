# 08 Notification system

## Opening prompt
"Design a notification system for our company's apps."

## Hidden requirements
- Q: Which channels? → A: Mobile push (iOS and Android), SMS and email; web push later.
- Q: What triggers notifications? → A: Events from backend services (order shipped, one-time passcodes, password resets) and scheduled marketing campaigns sent to whole segments. [critical]
- Q: What volume? → A: Per day: 10M push, 1M SMS, 5M email (16M total). A campaign can blast 5M pushes within 10 minutes. [critical]
- Q: Latency? → A: Transactional messages (passcodes, receipts) handed to the provider within 1 s and delivered within 5 s at p99; marketing within 30 minutes of its scheduled time. [critical]
- Q: Reliability? → A: Never lose a transactional notification; duplicates must be rare (under 0.1%) and never for payment confirmations. [critical]
- Q: User control? → A: Per-channel and per-category opt-out, quiet hours in the user's local time for marketing, and a legally required unsubscribe link in email.
- Q: Which providers? → A: Apple's and Google's push services, a third-party SMS vendor and a third-party email vendor; SMS must be able to fail over to a second vendor.
- Q: Limits? → A: At most 3 marketing notifications per user per day; the SMS vendor allows 100 messages/s per sender number.
- Q: Geography and privacy? → A: US and EU users; EU users' personal data stays in the EU; minimal personal data in logs.
- Q: Templates and languages? → A: 12 languages; marketing edits templates without engineering deploys.
- Q: Analytics? → A: Delivery status, opens and clicks, with reports within 15 minutes.
- Q: Security? → A: Only authenticated internal services may send; user-facing apps cannot call the send API.
- Q: Availability? → A: 99.95% for the transactional path; marketing may degrade during incidents.
- Q: Cost? → A: SMS is the expensive channel; use it for passcodes and as a critical fallback, not for marketing.
- Q: Device tokens? → A: Tokens change or expire; invalid tokens must be detected and removed.

## Back-of-envelope
- Average rates: 10M/86,400 ≈ 116 push/s; 1M ≈ 12 SMS/s; 5M ≈ 58 email/s; total ≈ 185/s.
- Peaks: a 5M-push campaign over 10 minutes = 8,333/s; transactional peak at 3x average ≈ 555/s. Design for about 10K/s on the push path.
- Push workers: if one worker sustains about 500 sends/s (assumption), 8,333 / 500 ≈ 17, so provision about 20.
- Queue traffic: 8,333 messages/s x 1 KB ≈ 8 MB/s, trivial for a log or queue cluster.
- Notification log: 16M x 500 B = 8 GB/day; 90 days ≈ 720 GB; one year ≈ 2.9 TB.
- Device tokens: 50M users x 1.5 devices x 200 B ≈ 15 GB.
- Dedupe store: 16M events/day x 40 B (event id plus status) = 640 MB/day; keeping 7 days ≈ 4.5 GB, small enough for an in-memory store.
- Cost (assumptions: SMS $0.008 each, email $0.0001 each, push free): SMS = 1M x $0.008 = $8,000/day; email = 5M x $0.0001 = $500/day; total $8,500/day. SMS is about 94% of cost and about $2.9M/year, so channel selection and fallback rules matter more than compute.

## Reference design
**Core API:**
- `POST /v1/notifications` body `{requestId, userId, category, templateId, params, channels[], priority: critical|normal|bulk, sendAt?, ttl}` returns 202 with `{notificationId}`.
- `POST /v1/campaigns` body `{segmentId, templateId, sendAt, maxRatePerSecond}`.
- `PUT /v1/users/{id}/preferences`, `POST /v1/devices` (register token).
- Provider webhooks (delivery receipts, bounces, complaints) land on a receipt endpoint.

**Data model:**
- `notifications(id, request_id UNIQUE, user_id, channel, template_id, status, attempts, provider_msg_id, created_at, sent_at)`.
- `preferences(user_id, category, channel, opt_in, quiet_start, quiet_end, timezone)`.
- `devices(user_id, platform, token, last_seen_at, valid)`.
- `templates(id, locale, version, body)`; `events(notification_id, type, ts)`.

**Flow:**
1. API authenticates the caller, checks the idempotency key (`requestId`), and writes the notification to the database (the outbox) in one step.
2. A dispatcher resolves preferences, quiet hours and per-user rate limits, picks channels and renders the template.
3. The message goes to a queue named by channel and priority (for example `sms-critical`, `push-bulk`).
4. Workers call a provider adapter, with retries (exponential backoff and jitter) and a circuit breaker per provider.
5. Provider receipts update status; after the retry limit the system falls back to another channel or vendor, or parks the message in a dead-letter queue and alerts.
6. Invalid-token responses mark the device invalid.

**Campaigns:** a scheduler expands the segment in pages and enqueues into bulk queues at the configured rate, so bulk traffic never starves transactional traffic.

**Components:** API servers, preference and device service, template service, dispatcher, per-channel per-priority queues, provider adapters, dedupe store, notification log, receipt handler, campaign scheduler, analytics pipeline, monitoring (queue depth, age of oldest message, provider error rates).

**Key trade-offs and rejected alternatives:**
- At-least-once delivery plus idempotency keys and dedupe (chosen) vs promising exactly-once: exactly-once across a network and a third-party provider is not achievable; make retries safe instead.
- Separate queues per channel and priority (chosen) vs one queue: one queue lets a marketing blast delay a passcode.
- Persist before enqueue with an outbox (chosen) vs writing the database and the queue separately: separate writes can drop or duplicate on a crash between them.
- Synchronous send in the API call vs asynchronous (chosen): provider latency and outages would otherwise leak to callers.
- Single provider vs multiple providers per channel: multiple providers add adapter cost but protect against outages and regional gaps (for example, Google's push service is unavailable in China).
- Build providers vs buy: deliverability, carrier relationships and reputation are hard; buy the last mile, build the orchestration.

## Deep-dive topics
- Walk through the crash windows: after sending but before recording status, and after recording but before sending. Which produces a duplicate and which a loss, and what do you do about each?
- How is the dedupe check made atomic and how long are keys kept?
- How do you stop a 5M-message campaign from delaying one-time passcodes?
- What do you do when a provider is slow or down, and how do circuit breakers and fallback work?
- How do you honor quiet hours across time zones for a scheduled campaign?
- How do you handle bounces, spam complaints and invalid device tokens?
- How do you rate limit per user across channels without a global lock?
- How do you keep EU personal data in the EU while one logical system serves both regions?

## Curveballs
- Volume grows 100x because a new product sends a notification for every event. What breaks first?
- The SMS vendor has a three-hour outage during a login rush. What happens to passcodes?
- Marketing sends the same campaign twice by mistake. How could the system have caught it?
- Legal says users who opt out must stop receiving messages within one minute. How is that enforced with data already queued?
- Product wants guaranteed in-order delivery per user. What does that cost?

## Red flags
- Sending directly from API request handlers with no queue.
- One shared queue for all message types and priorities.
- Claiming exactly-once delivery.
- No idempotency key, so a retried API call sends twice.
- Ignoring opt-outs, quiet hours or legal unsubscribe requirements.
- No handling of invalid tokens, bounces and complaints.
- No fallback or circuit breaker for provider outages.
- Treating all channels as equal cost.
- Dedupes by checking whether the event ID was seen without atomicity: record-then-send loses the event on a crash, send-then-record duplicates it; needs an outbox or idempotency key
- Ignores priority lanes (passcode versus marketing), campaign throttling, bounce and complaint handling, invalid-token cleanup, quiet hours and SMS or email compliance
- Secures service-to-service sends with a static app key and secret instead of short-lived tokens or mutual TLS

## Rubric signals
| Level | Weak | Hire | Strong hire |
| --- | --- | --- | --- |
| Mid | Direct calls to providers from one server | Queues, workers, per-channel adapters, template and preference checks, retry | Does the rate and cost math, persists before enqueue, and separates priorities |
| Senior | Promises exactly-once or ignores crash windows | At-least-once with idempotency and dedupe, dead-letter queue, circuit breakers, campaign throttling | Walks through each crash window, designs outbox and dedupe atomically, handles tokens, bounces and quiet hours |
| Staff | Treats it as plumbing | Frames it as a platform with SLOs per priority, vendor strategy and compliance | Designs for provider failure, regional data rules, cost control by channel, and a self-service model for product teams |
