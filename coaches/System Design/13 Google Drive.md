# 13 Google Drive

## Opening prompt
"Design a cloud file storage and sync service like Google Drive or Dropbox. Users put files in a folder on one device and they show up on their other devices and in the browser. They can also share files with other people."

## Hidden requirements
- Q: Which features are in scope? → A: Upload, download, automatic sync across devices, file revision history, sharing, and change notifications. Real-time collaborative document editing is explicitly out of scope. [critical]
- Q: Which clients? → A: Desktop sync client, mobile apps and web. The same account usually has several devices, and each one needs its own idea of "what have I already seen".
- Q: What scale? → A: 50M registered users, 10M DAU, a 10 GB free quota per user. The candidate should notice that the 500 PB figure from quota multiplication is allocated space, not used space.
- Q: What is the usage profile? → A: Each active user adds about 2 files per day at an average of 500 KB, with a 1:1 read to write ratio. A stronger candidate asks whether modern files (photos, videos, office documents) are larger and adjusts.
- Q: What is the maximum file size? → A: 10 GB. Uploads must be resumable and downloads must support ranges.
- Q: What must never happen? → A: Data loss, and a client silently overwriting someone else's changes. Durability and conflict safety rank above latency. [critical]
- Q: What consistency does the user see? → A: Metadata (names, folder tree, versions) must be strongly consistent: two clients must not see different latest versions of a file as the truth. File content blocks are immutable, so they carry no consistency problem. [critical]
- Q: How fast is "fast sync"? → A: A change on one device should appear on others in seconds, and sync must move only what changed to save bandwidth on metered mobile connections.
- Q: What happens on conflicting edits? → A: Never lose either version. The loser gets a conflict copy or a prompt, and the user decides. Concurrent text merging is out of scope.
- Q: Is encryption required? → A: Yes. TLS in transit and encrypted at rest. The candidate should say who holds the keys and what that means for deduplication and server-side features. End-to-end client-side encryption is a follow-up.
- Q: How does sharing work? → A: Share a file or folder with specific users or via link, with view or edit permission. Permissions are checked on every metadata and block access.
- Q: How are versions kept? → A: Users can list and restore past revisions. Retention is a cost lever: version count or age limits, and cold storage for old data. [critical]
- Q: What if a client is offline for weeks? → A: It reconnects, asks for changes since its last known point and catches up. It must not require the server to hold per-client queues forever.
- Q: Are deletes recoverable? → A: Yes, through a trash with retention. Deleting is a metadata operation, and block garbage collection happens later.

## Back-of-envelope
- Upload requests: 10M × 2 = 20M files per day, about 231 per second (rounding to 240 is common). Peak at 2× is about 480 per second, but real peaks come from bulk first syncs (a user dropping a 100K-file folder), so plan for bursts well above 3×.
- New data: 20M × 500 KB = 10 TB per day, about 3.7 PB per year. If the true average file is 2 MB (photos, PDFs), it is 40 TB per day and about 15 PB per year. Storage needed is driven by actual usage, not quota. The 500 PB figure is the quota ceiling (50M × 10 GB). Real utilization is typically a small fraction, so a plausible provisioned figure is tens of PB.
- Blocks: with 4 MB blocks, the average 500 KB file is a single block, a 1 GB file is about 250 blocks and a 10 GB file about 2,560. Assume about 1.3 blocks per file, so about 26M block records per day, about 9.5B per year at about 100 B each is about 1 TB per year of block metadata.
- File metadata: about 500 B per file and per version, so 20M files × 0.5 KB = 10 GB per day, about 3.6 TB per year before versions. This fits sharded relational storage comfortably, so the hard part is correctness, not size.
- Downloads and sync fan-out: if the average user has 3 devices, each upload is pulled by 2 other devices, giving about 40M block fetches per day, about 460 per second, and about 20 TB per day of egress, roughly 1.9 Gbps average. Modest.
- Notification connections: peak concurrent online users at 30% of 10M DAU is about 3M connections. At 100K–300K connections per server, that is 10–30 notification servers.
- Long poll re-request rate: if each long poll times out and reconnects every 60 seconds, 3M clients produce about 50K requests per second just to stay subscribed, which is over 100× the upload rate. Connection renewal, not file uploads, is the largest request volume in this system. WebSockets or SSE avoid most of it.
- Dedup savings: assume 20–30% of blocks are duplicates inside an account or tenant. Savings are a hypothesis to validate with production data, and cross-user dedup has security side channels.
- Reconnect storm: a notification server with 300K long-poll connections that dies makes 300K clients reconnect together. With jittered backoff over 60 seconds that is about 5K reconnects per second per failed server, which is manageable. Without jitter it is a spike.

## Reference design
**Core API.**
- POST /files (parent_id, name, size, block_hashes[]) creates or updates a file version and returns which blocks the server still needs. Content-addressed, so already-known blocks are skipped.
- PUT /blocks/{hash} uploads one block (resumable), or direct-to-storage via pre-signed URL.
- POST /files/{id}/commit (base_version) atomically makes a new version visible if base_version is still the current one, else returns a conflict.
- GET /changes?cursor=<c> returns a page of metadata changes since the cursor plus a new cursor. This is the sync primitive.
- GET /files/{id}/versions, POST /files/{id}/restore, POST /shares, DELETE /files/{id} (move to trash).
- Identify files by immutable file_id, not by path, so renames and moves are cheap metadata edits.

**Data model.**
- User, Device (device_id, user_id, push token, last cursor).
- Namespace or folder tree: node (node_id, parent_id, name, type, owner, current_version, deleted flag). Path is derived.
- File version: (file_id, version, size, mtime, ordered list of block hashes, author, created_at). Immutable once written.
- Block: (hash → storage location, size, refcount). Blocks are content-addressed and immutable.
- Change log: per namespace (or per user) append-only log with a monotonic sequence number. Cursor equals the last sequence number a device processed.
- ACL: (node_id, principal, role) with inheritance from parent folders.
- Metadata store: sharded relational (strong consistency, transactions), sharded by namespace or account so a user's tree and its change log sit in one shard. Block bytes live in object storage.

**Components.**
- Clients split files into blocks (fixed or content-defined), hash them, and ask the server which hashes are missing. Compression, then encryption, happens before upload.
- API servers (stateless) for metadata and permission checks.
- Block upload goes direct to object storage via pre-signed URLs (fast, no proxy bottleneck) or through block servers when server-side processing is wanted (a common choice).
- Metadata service performs the transactional commit and appends to the change log in the same transaction.
- Notification service tells clients "namespace X has new changes" over a long-lived connection. The message carries no data, just a hint. The client then calls /changes with its cursor.
- Async workers: thumbnails, virus scanning, search indexing, refcount maintenance and garbage collection of unreferenced blocks, tiering old versions to cold storage.

**Key trade-offs and rejected alternatives.**
- Content-addressed immutable blocks make dedup, resumable upload, delta sync and idempotent retries easy. The price is hash computation on the client and garbage collection with reference counting.
- Fixed 4 MB blocks are simple but an insertion near the start of a file shifts every later block boundary, so almost nothing deduplicates. Content-defined chunking (rolling hash, as in rsync-style tools) fixes that, at CPU cost and variable block sizes.
- Strong consistency for metadata with a single leader per shard and synchronous replication, instead of reading from lagging replicas. Block reads can come from anywhere because blocks are immutable.
- Optimistic concurrency (commit with base_version) instead of locking files: two devices editing offline cannot hold a lock, so detect and branch, never overwrite.
- Cursor-based change feed instead of per-device queues: state per device is one integer, a device offline for weeks just replays the log, and the server stores no per-device backlog.
- Long polling versus WebSocket or SSE for notifications: long polling works through restrictive proxies and is fine for rare events, but the renewal traffic is large at millions of clients.
- Server-side encryption keeps dedup, search and previews possible. Client-side (end-to-end) encryption removes provider access and breaks all three.

## Deep-dive topics
- How does a client know which blocks to upload, and how do resumable upload and retries stay idempotent?
- How do you detect and resolve a conflict when two devices edit the same file offline, and what exactly does the server compare?
- How does the change feed work, and how does a device that was offline for a month catch up safely?
- Why content-defined chunking beats fixed-size blocks for delta sync, and what does it cost?
- How do you keep the metadata strongly consistent and still scale, and what role does the cache play without causing stale reads?
- How do you garbage-collect blocks safely when versions expire and many files share the same block?
- How do permissions and sharing interact with the sharding key and with the change feed of the recipient?
- How do you handle a notification server failure with hundreds of thousands of connected clients?

## Curveballs
- A user renames a folder containing 200,000 files. Ask what happens to the metadata, the change feed and the other devices. A path-keyed design fails here, an ID-based tree does not.
- Two users share a folder and one moves it into a folder the other cannot see. Ask what each user's change feed shows.
- A bug uploads corrupted blocks for six hours. Ask how you detect it (hash verification, scrubbing), find affected versions, and recover.
- Legal asks for hard deletion of a file and all versions within 24 hours while other users dedupe against the same blocks. Ask how reference counting, cold storage and backups cope.
- Product wants real-time co-editing of documents. Expect a clear boundary: this needs operational transformation or CRDTs and a different service, not an extension of block sync.

## Red flags
- Storing files in a database blob or on the web server disk with no durability story.
- Uploading whole files on every change with no block or delta idea.
- "Last write wins" for file conflicts without realizing it destroys user data.
- Reading metadata from asynchronous replicas while claiming strong consistency.
- Per-device message queues as the only sync mechanism, with no cursor or change log.
- Path-based identity, making rename and move expensive and fragile.
- Ignoring permissions and sharing completely.
- No plan for deleting unreferenced blocks, or deleting blocks that are still referenced.
- Common misconception: treats 500 PB (50M users x 10 GB) as expected stored data; it is allocated quota, and capacity planning should use actual usage
- Common misconception: requires strong consistency yet reads metadata from asynchronous replicas; stale reads contradict the requirement unless reads go to the leader or synchronous replicas
- Common misconception: invalidating the cache on database write is enough; invalidation races (a reader refilling the cache with the old value just after the write) need versioned keys, short TTLs or write-through
- Common misconception: resolves conflicts with first version processed wins and no mechanism; without a base-version check on commit the server cannot tell a conflicting write from a normal update
- Common misconception: delta sync with fixed 4 MB blocks handles any edit; it works only for in-place edits, and insertions need rolling hashes as in rsync
- Uses file paths as identifiers, so renaming or moving a folder changes every descendant's identity; file IDs avoid this
- Uses an offline backup queue per client where a cursor into a change log is simpler and safer
- Ignores sharing and permissions, reference counting and garbage collection of shared blocks, and how encryption interacts with dedup
- Forgets that cold storage tiers have retrieval delays and minimum storage durations

## Rubric signals
- Mid, weak: web server, database and file system, with no replication, chunking or notification idea.
- Mid, hire: object storage for content, relational metadata, block-based upload with resumability, notification to other devices, and a basic conflict approach.
- Mid, strong-hire: content-addressed blocks, "which blocks are missing" handshake, versioned immutable file records, and a cursor-based pull of changes.
- Senior, weak: reproduces the textbook design without noticing the consistency or path-identity weaknesses.
- Senior, hire: optimistic commit with base_version, change log inside the metadata transaction, dedup and version retention with cost estimates, and failure handling for notification servers.
- Senior, strong-hire: content-defined chunking, garbage collection with reference counting, ACL inheritance and sharing impact on sharding, and quantifies renewal traffic of long polling.
- Staff, weak: describes components but cannot say which invariants the system protects (no lost data, no silent overwrite) or how to verify them.
- Staff, hire: designs for integrity (end-to-end hashes, scrubbing, repair), multi-region placement and disaster recovery, and capacity planning from real usage rather than quota.
- Staff, strong-hire: reasons about the encryption versus dedup versus search trade-off as a product decision, designs safe migration of metadata shards, and defines sync correctness tests and metrics.
