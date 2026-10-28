# 12 YouTube

## Opening prompt
"Design a video sharing platform like YouTube. Creators upload videos and viewers watch them on phones, browsers and smart TVs. Focus on upload and playback."

## Hidden requirements
- Q: Which features are in scope? → A: Upload a video and watch a video. Comments, likes, subscriptions, recommendations and search are out of scope. The candidate should say so out loud and not drift. [critical]
- Q: Which clients? → A: Mobile apps, web browsers and smart TVs. Devices differ in supported codecs and formats, and bandwidth varies a lot, so one file cannot serve everyone. [critical]
- Q: What scale? → A: 5M DAU, 5 videos watched per user per day, 30 minutes average daily watch time. The candidate should notice that 30 minutes over 5 videos is about 6 minutes per view.
- Q: How many uploads and how large? → A: Assume 10% of DAU upload one video per day at 300 MB average, so about 500K uploads per day. Hard cap of 1 GB per video.
- Q: Which resolutions and formats are accepted? → A: Most formats and resolutions. The platform normalizes them, so the candidate must design transcoding.
- Q: What must playback feel like? → A: Fast start (first frame in about 2 seconds or less), little rebuffering, and automatic quality switching when bandwidth changes. [critical]
- Q: How fast must an uploaded video become watchable? → A: Minutes, not hours. The lowest-resolution rendition should appear first and higher ones follow.
- Q: Is "encryption required" about transport or content protection? → A: Both are in play. TLS everywhere is assumed. Content protection (DRM or encrypted segments) is a discussion topic for premium content, and the candidate should ask which the interviewer means.
- Q: Are international viewers important? → A: Yes. Global delivery through a CDN is mandatory. Where originals are stored and how uploads are accepted close to the creator are design choices.
- Q: Can we use cloud services? → A: Yes. Blob storage and a managed CDN are expected. The interviewer wants good technology choices, not a rebuilt S3. [critical]
- Q: What happens when an upload is interrupted or transcoding fails? → A: Uploads must be resumable. Failed transcoding steps must be retried without redoing the whole video. Malformed files get a clear error.
- Q: What are the cost expectations? → A: Low infrastructure cost is an explicit goal. CDN egress dominates, so the candidate must say how they would reduce it.
- Q: Is moderation or copyright takedown in scope? → A: Not as a feature, but the design should leave a place for pre-publication checks and removal of a video from all caches.

## Back-of-envelope
- Uploads: 5M × 10% = 500K videos per day, about 6 per second on average and about 20 per second at peak (3×). Low request rate, large bodies.
- Upload ingress: 500K × 300 MB = 150 TB per day, about 1.7 GB per second on average (about 14 Gbps) and about 40 Gbps at peak. The 10% uploader share is far higher than any real platform, so call it out as a generous assumption.
- Storage: originals 150 TB per day (about 55 PB per year). Transcoded renditions add roughly 1.5–2× the original size across a typical bitrate ladder, so about 225–300 TB per day more, which is 100–110 PB per year in renditions. Blob storage providers handle durability replication, but it is still billed storage. Cold-tiering originals is a real saving.
- Views: 5M × 5 = 25M views per day, about 290 views per second on average and about 900 per second at peak. Metadata reads are small compared with segment fetches.
- Egress (naive): 25M views × 0.3 GB = 7.5 PB per day, about 700 Gbps average. At $0.02 per GB that is $150K per day.
- Egress (from watch time): 30 minutes per user per day at about 2.5 Mbps is about 560 MB per user per day. 5M users × 0.56 GB = 2.8 PB per day, about 260 Gbps average and about 800 Gbps at peak. At $0.02 per GB that is about $56K per day, about $1.7M per month. The two estimates differ by about 2.7× because the naive estimate uses the upload size as the streamed size per view, which is wrong when users watch partial videos at adaptive bitrates.
- Transcoding compute (rough assumption): about 4 core-minutes per minute of video across the whole ladder. 500K videos × about 8 minutes average × 4 = 16M core-minutes per day, about 11K cores running continuously and about 30K at peak. Hardware encoders or GPUs reduce this, and the number depends heavily on codec choice.
- Metadata: about 1 KB per video, so 500 MB per day. Trivial. Sharding the metadata database is a resilience question, not a capacity one.
- View counting: 290 events per second average is easy, but a viral video concentrates writes on one key, so batch or shard the counter.

## Reference design
**Core API.**
- POST /videos returns video_id and an upload URL (pre-signed, multipart or resumable), with the declared size and content type.
- Client uploads parts directly to blob storage, then calls POST /videos/{id}/complete.
- PUT /videos/{id}/metadata (title, description, visibility).
- GET /videos/{id} returns metadata and the manifest URL (HLS .m3u8 or DASH .mpd) on the CDN.
- Playback itself is HTTP GET of manifest then segments from the CDN, with no API server in the loop.

**Data model.**
- Video: video_id, owner_id, status (uploading, processing, ready, failed, removed), original object key, duration, created_at, visibility, list of renditions (resolution, codec, bitrate, manifest path).
- Upload session: upload_id, video_id, parts received, expiry.
- Transcode job: job_id, video_id, DAG node, attempt count, state. Needed for retry and idempotency.
- Blob storage layout: originals (cold-tier after processing), segments per rendition, manifests, thumbnails.
- Metadata database: relational or sharded KV, replicated, behind a cache. Small and read-heavy.

**Components.**
- Edge and API tier: stateless API servers behind a load balancer, issuing pre-signed URLs and handling metadata.
- Upload path: client to blob storage directly (nearest region or edge ingest), in parts, resumable. API servers never see the bytes.
- Upload-complete event goes to a durable queue. A preprocessor validates the file (inspection) and cuts it at keyframe (GOP) boundaries into segments.
- DAG scheduler: expands the pipeline for the video (encode ladder, audio, thumbnails, watermark, moderation) into tasks and enqueues them. Workers pull tasks, process one segment or one rendition, write results to blob storage and report completion.
- Packager produces HLS and DASH manifests (CMAF fragmented MP4 lets one set of segments serve both).
- Completion handler marks renditions ready and flips status, so the video can be published once the first rendition completes.
- CDN: pulls segments from the origin on demand with a shield tier, caches by popularity. Playback clients use adaptive bitrate: they read the manifest and switch renditions per segment based on measured throughput.
- Takedown path: status change plus CDN purge plus manifest removal.

**Key trade-offs and rejected alternatives.**
- Direct-to-blob upload with pre-signed URLs instead of proxying through API servers: avoids a bandwidth bottleneck and cost, but needs size and content-type limits, short expiry, and post-upload validation.
- Segment-level parallel transcoding instead of one job per video: faster time to ready and cheap retries, but needs keyframe-aligned splitting and a stitch or manifest step.
- Adaptive bitrate streaming (HLS or DASH) versus progressive download of one file: ABR costs storage for multiple renditions and encoding compute, but is what makes quality switching and fast start work on bad networks.
- Pull CDN versus push to CDN: pull handles the long tail naturally and avoids pre-distributing videos nobody watches, push guarantees warm edges for known hits. Hybrid (pre-warm very popular new uploads) is typical.
- Encode the full ladder up front versus encode lazily: long-tail videos can get a few renditions at upload and more only if they become popular, trading compute for storage.
- Build versus buy CDN: buy until egress is large enough to justify ISP-embedded caches (the Netflix route).
- Queue-driven coupling instead of synchronous steps: tolerates worker failures and uneven load, at the price of at-least-once execution that needs idempotent tasks.

## Deep-dive topics
- How does a 1 GB upload survive a flaky mobile network, and what does resume look like on the wire?
- Why transcode into many renditions, and how do you choose the bitrate ladder and codecs for phones versus smart TVs?
- How do you split a video for parallel transcoding, and how do you make the pipeline retry-safe and idempotent?
- How does the player choose and switch quality mid-playback, and what do the manifest and segment length control?
- Where does the money go at this scale, and which three changes cut CDN spend the most?
- How do you handle a video that suddenly goes viral after sitting unwatched for a year?
- How do you stop unauthorized uploads and content theft (pre-signed URL limits, encrypted segments, DRM)?
- How do you remove a video everywhere quickly, including CDN caches and offline copies?

## Curveballs
- Add live streaming. Expect discussion of a different ingest protocol, much lower latency targets, short segments, and loss of the transcode-then-publish luxury.
- Traffic is 10× what you planned in one region after a celebrity upload. Ask what saturates first (origin, CDN shield, metadata cache) and how to protect it.
- A bug in the encoder produced corrupted 480p renditions for the last six hours. Ask how to detect, reprocess and replace them without breaking playback.
- The interviewer asks to cut egress cost by 40%. Expect options: better codecs (AV1, HEVC) versus device compatibility, ABR ladder tuning, regional pinning, cache hierarchy and ISP caches.
- A copyright holder claims a video that has been cached in the CDN worldwide. Ask for the sequence of actions and how quickly it takes effect.

## Red flags
- Streaming a single large file from the API servers or from the blob store directly with no CDN.
- No transcoding story, or one output format for all devices.
- Routing uploaded bytes through application servers.
- Uploading as one monolithic request with no resumability.
- Ignoring cost, or failing to see that egress dominates.
- No retry, idempotency or status model for a long multi-step pipeline.
- Treating the CDN as the only copy of a video.
- Spending the whole interview on features (comments, recommendations) that were declared out of scope.
- Common misconception: estimates egress from the average uploaded file size (0.3 GB per view) instead of bytes actually streamed; 30 minutes at about 2.5 Mbps is about 0.56 GB per user per day
- Common misconception: counts only original uploads in storage; transcoded renditions add substantially more
- Common misconception: splits the video on GOP boundaries on the client for resumable upload; resumability comes from byte-range multipart or resumable-upload protocols (S3 multipart, tus), and keyframe-aligned splitting is done server-side for parallel transcoding
- Common misconception: lists Adobe HDS or Smooth Streaming as current; practice is HLS and MPEG-DASH, commonly with CMAF segments
- Common misconception: says videos are stored in the CDN; a CDN is a cache and the durable copy lives in blob storage
- Describes failover as promote-a-replica without split-brain or replication-lag concerns
- Assumes CDN list price is $0.02 per GB; that is a deep-volume or negotiated tier, and low-volume list prices are several times higher
- Ignores signed URL restrictions (size, content type, expiry), post-upload malware and moderation scanning, and view-count write hot spots

## Rubric signals
- Mid, weak: draws client, API server, database and a video file store, but has no transcoding or CDN and no explanation of how playback adapts.
- Mid, hire: separates upload and playback flows, uses blob storage plus a CDN, explains why transcoding exists and uses a queue between upload and processing.
- Mid, strong-hire: adds resumable direct-to-blob uploads with pre-signed URLs, segment-based parallel transcoding, and computes egress cost from watch time.
- Senior, weak: repeats the textbook diagram without questioning figures, cannot justify codec or protocol choices.
- Senior, hire: designs ABR with HLS or DASH, a task DAG with retries and idempotency, cost levers (long tail, regional pinning, lazy encoding) and a status model.
- Senior, strong-hire: separates upload chunking from GOP segmentation, treats CDN as a cache over durable origin, plans for viral spikes and re-encoding campaigns, and notices which book numbers disagree.
- Staff, weak: optimizes pipeline details but cannot connect them to unit economics or product experience.
- Staff, hire: frames the design around cost per streamed hour and time-to-first-play, covers multi-region ingest and origin placement, and defines operational metrics (startup time, rebuffer ratio, transcode lag).
- Staff, strong-hire: reasons about codec roadmap versus device base, custom CDN or ISP partnerships by threshold, content security and legal takedown processes, and staged migration of the pipeline.
