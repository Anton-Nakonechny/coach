# 10 Chat system

## Opening prompt
"Design a chat application like Messenger or WhatsApp. People should be able to message each other one-to-one and in small groups, from phone and from the browser, and see who is online. Walk me through how you would build it."

## Hidden requirements
- Q: Is it one-to-one only, or groups too, and how large can a group be? → A: Both. Groups are capped at 100 members. Large broadcast channels (Discord or Telegram style) are out of scope, but the candidate should say where the design would break if the cap were lifted. [critical]
- Q: What scale? → A: 50M DAU. Assume about 40 messages sent per user per day and peak concurrency of 20–30% of DAU, so roughly 10–15M simultaneous connections.
- Q: Which clients? → A: iOS, Android and web. The same account can be logged in on several devices at once, and all of them must converge on the same history. [critical]
- Q: What content is supported? → A: Text only for now, up to 100,000 characters per message. Media is a follow-up topic.
- Q: How long is history kept, and must it be searchable? → A: Forever. Mostly recent reads, but scrolling back and jumping to a message must work. Full-text search is out of scope unless time allows.
- Q: What ordering guarantee is needed? → A: Messages inside one conversation must appear in the same order for every participant. There is no need for ordering across conversations. [critical]
- Q: What delivery guarantee is needed? → A: No message may be lost once the sender has been told "sent". Duplicates must never be visible to the user. The candidate has to explain how retries and idempotency produce this. [critical]
- Q: What latency target? → A: p99 end-to-end under about 500 ms when both parties are online, and under a second for the group case.
- Q: Is presence (green dot) required and how fresh must it be? → A: Yes. Staleness of 10–30 seconds is fine, and flapping through tunnels should not be visible.
- Q: What happens when the recipient is offline? → A: The message is stored and a push notification is sent. On reconnect the device syncs everything it missed.
- Q: Is end-to-end encryption required? → A: Not now. TLS in transit and encryption at rest are assumed. E2EE is a discussion topic: key exchange, multi-device keys, and what it does to server-side search and group fan-out.
- Q: Are read receipts or typing indicators needed? → A: Typing indicators and read receipts are nice to have. They must not be allowed to threaten the core path, so they should be best-effort and lossy.
- Q: Availability and regions? → A: Highly available, with no single point of failure. A single region is acceptable for the interview. Multi-region is a follow-up.
- Q: Can users block each other, and are there spam controls? → A: Out of scope. A candidate who mentions rate limiting per sender earns credit.

## Back-of-envelope
- Messages: 50M DAU × 40 sent per day = 2B messages per day, about 23K per second on average. Peak of 3× gives about 70K per second.
- Fan-out: assume 80% of messages are one-to-one (1 recipient) and 20% are group with an average of 20 members. Deliveries per message = 0.8×1 + 0.2×20 = 4.8. That gives about 9.6B deliveries per day, about 110K per second on average and about 330K per second at peak. Deliveries are the number that drives the WebSocket tier and the inbox writes.
- Connections: 50M DAU, peak concurrent 25% gives about 12M WebSockets. At 100K connections per chat server (conservative, with TLS buffers and headroom) that is about 120 servers. Round up to about 200 for N+1, rolling deploys and a regional spike.
- Connection memory: a common textbook figure is 10 KB per connection (10 GB for 1M). With TLS and application buffers 20–50 KB is more realistic. At 12M connections that is roughly 250–600 GB of RAM across the fleet, which is easy when spread over 200 servers.
- Storage per message: about 100 bytes of text on average plus roughly 100 bytes of metadata (ids, sender, timestamps, flags) gives about 200 bytes. 2B messages per day × 200 B = 400 GB per day, about 150 TB per year before replication. With 3× replication about 450 TB per year.
- Write path: the conversation store takes one write per message, not per delivery (about 23K/s avg, 70K/s peak). Per-recipient inbox or sync-cursor updates are lighter if designed as a pointer rather than a copy.
- Presence: if 12M clients heartbeat every 30 seconds, that is about 400K heartbeats per second. This is cheap if heartbeats are piggybacked on WebSocket pings and only write to a TTL store. A 5 s heartbeat would be 2.4M per second for the same fleet, which is wasteful.
- Presence fan-out: a user with 200 friends online generates 200 pushes per status flip. With debouncing (only emit after the status has been stable for the timeout) this stays tolerable. Without it, a flapping user is a self-inflicted DDoS.
- Bandwidth: 330K deliveries per second × about 300 B on the wire is about 100 MB per second, roughly 0.8 Gbps at peak. Chat is connection-bound, not bandwidth-bound.

## Reference design
**Core API.** Most traffic is on one WebSocket per device, with a small request/response API for everything else.
- Over WebSocket: send_message(conversation_id, client_msg_id, body), ack(message_id), sync(since_cursor), typing(conversation_id), heartbeat.
- Server pushes: message(conversation_id, message_id, sender, body, ts), presence(user_id, state), read_receipt.
- Over HTTPS: login, profile, create group, add or remove member, fetch history page (conversation_id, before_message_id, limit), register push token.

**Data model.**
- Messages are partitioned by conversation_id and clustered by message_id, in a wide-column or KV store such as Cassandra, HBase or DynamoDB. Row: (conversation_id, message_id) → sender_id, body, created_at, client_msg_id.
- Message_id is monotonic within a conversation. Options: a per-conversation sequence assigned by the owning chat shard, or a time-ordered 64-bit ID (snowflake style, see case 05). Timestamps are not used for ordering because two messages can share a millisecond.
- Conversation membership: (conversation_id → member list) and (user_id → conversation ids), in a relational or KV store. This is small relative to messages.
- Per-device sync state: (user_id, device_id) → per-conversation last-seen message_id. This is what makes multi-device sync work. A single cur_max_message_id per device only works with a globally ordered ID.
- Presence: user_id → last_heartbeat with a TTL, in an in-memory store (Redis). Never in the durable message store.
- Generic data (profile, settings, friends) in a relational database with replication and sharding.

**Components.**
- Edge: L4 load balancer terminating TLS, then API servers for stateless calls.
- Service discovery (ZooKeeper, etcd or simply a connection-routing service) gives a client a chat server hostname based on region and load.
- Chat servers hold the WebSockets. They are the only stateful tier and are stateful only in the sense of holding connections.
- Session registry: user_id/device_id → chat server, with a TTL, kept in Redis or similar. This is how any chat server finds where a recipient is connected.
- Message path: sender's chat server validates, assigns the message_id, durably appends to the message store (and to a log or queue for async consumers), acks the sender, then looks up each recipient in the session registry and forwards to their chat server over an internal channel (gRPC or a pub/sub topic per chat server).
- Offline path: no entry in the registry means a push notification via the notification system (case 08). On reconnect the client sends its cursors and pulls the gap from the message store.
- Presence servers consume heartbeats and connect/disconnect events, debounce, and publish changes.
- Async consumers: push notifications, search indexing, analytics, and unread counters.

**Key trade-offs and rejected alternatives.**
- Transport: WebSocket for both directions. Polling wastes requests. Long polling suffers because stateless servers may not hold the open request and cannot detect disconnects well. HTTP for sending is acceptable, but a single bidirectional channel simplifies the client and reduces handshakes.
- Persist before forward. The message is durable before the sender is told "sent". Forwarding is then an optimization, and the sync-on-reconnect path is the source of truth. This makes lost forwards harmless.
- Delivery is at-least-once on the wire, with client_msg_id dedupe on the server and message_id dedupe on the client, which gives effectively-once for the user.
- Group fan-out: for up to 100 members, one stored message per conversation and a per-member push is the better design. Copying each message into every member's inbox is a common alternative, but it multiplies storage by group size and gets worse as groups grow. Copy-per-inbox is only worth it for tiny groups, and even then storing a pointer is cheaper.
- Message store: wide-column or KV by conversation_id. A relational database struggles with the long tail of old conversations, though sharded relational (Vitess, Citus) is a viable alternative if the team prefers it. Say why you chose.
- Ordering: single writer per conversation (the shard that owns the conversation assigns IDs) is simple and strict. The alternative is a global ID or Lamport-style clock, which gives only a causal or approximate order and complicates gap detection.
- Presence over pub/sub per friend pair explodes the channel count. A per-user topic that interested friends subscribe to, with lazy fetch for big contact lists, scales better.

## Deep-dive topics
- How do you guarantee ordering inside a conversation when the sender's two messages may go through different servers or retries?
- How do you make sending idempotent so a client retry after a timeout never produces a duplicate?
- How does a device that was offline for three days catch up, and how do you avoid sending it everything in one burst?
- How do you find which server holds a recipient's connection, and what happens when that server dies mid-delivery?
- How do you handle presence flapping, and how do you deliver presence to hundreds of friends without a thundering herd?
- Why one stored copy per group message instead of one per member, and where does that choice stop working?
- How do you do a rolling deploy of chat servers without 10M clients reconnecting in the same minute?
- How would end-to-end encryption change the design, especially for groups and multiple devices?

## Curveballs
- Raise the group cap from 100 to 100,000 and ask what breaks first (fan-out, unread counts, presence). The expected answer shifts to pull-based delivery for large groups and lazy presence.
- A chat server crashes with 200K connections. Ask what the user sees, how fast clients reconnect, and how to avoid a reconnect storm (jittered exponential backoff, connection draining, admission control).
- Two devices of the same user send at the same instant in the same conversation. Ask for the final order and how both devices agree on it.
- The interviewer adds "message edit and delete for everyone". Ask how it propagates to offline devices and how it interacts with the cursor-based sync.
- Product asks for 1:1 messages to be searchable within two seconds. Ask what changes given the sync and storage choices (async indexer off the message log, per-user index, privacy under E2EE).

## Red flags
- Polling or long polling as the final answer for real-time delivery with no discussion of why.
- A single global database or a single chat server with no failure story.
- Ordering by client or server timestamp.
- No acknowledgement or retry design, so "no loss" is claimed but not engineered.
- Storing presence in the durable store or heartbeating every few seconds with no cost estimate.
- Copying every group message into every member's inbox without mentioning the storage multiplier.
- Not distinguishing the stateful WebSocket tier from the stateless API tier.
- Ignoring multi-device sync, or assuming one device per user.
- Common misconception: syncs with one cur_max_message_id per device; that works only if message IDs are globally ordered across all conversations, while per-conversation sequence numbers need per-conversation cursors
- Common misconception: uses one pub/sub channel per friend pair for presence; the channel count grows with friendships, and per-user topics with debounce are standard
- Common misconception: heartbeats every 5 s with a 30 s timeout at scale; at about 12M connections that is roughly 2.4M events per second, so 20-30 s heartbeats piggybacked on WebSocket pings are a better default
- Common misconception: calls the per-recipient message sync queue a queue; it is a durable per-recipient inbox that must survive restarts, and calling it a queue hides the storage cost
- Common misconception: budgets 10 KB per connection; that is a lower bound, TLS and application buffers push it to tens of KB, and fitting 1M connections on one machine says nothing about availability
- Omits acknowledgements, retries, client-side idempotency keys and ordering under retries

## Rubric signals
- Mid, weak: names WebSocket and a database but cannot explain ordering, offline delivery or how a message reaches a recipient on another server.
- Mid, hire: correct end-to-end flow for 1:1 and offline, persists then forwards, uses a session registry, picks a sensible store, estimates QPS and connections.
- Mid, strong-hire: adds client_msg_id idempotency, multi-device cursors, heartbeat-based presence with debounce, and quantifies fleet size.
- Senior, weak: reproduces the textbook flow without questioning it, hand-waves delivery guarantees and group scaling.
- Senior, hire: defends ordering design (single writer per conversation), at-least-once plus dedupe, group fan-out choice with numbers, and failure handling for a chat server crash.
- Senior, strong-hire: spots the multi-device cursor inconsistency with per-conversation IDs, plans reconnect storms and rolling deploys, and sizes presence traffic realistically.
- Staff, weak: treats chat as a library of components and cannot prioritize or articulate which guarantee is the product.
- Staff, hire: frames the design around the durability-before-delivery invariant, covers multi-region placement of conversations and a migration story, and discusses operational limits of the connection tier.
- Staff, strong-hire: reasons about cost per delivered message, E2EE implications for the whole architecture, abuse and spam controls, and how the system degrades gracefully (presence and typing drop first, delivery never).
