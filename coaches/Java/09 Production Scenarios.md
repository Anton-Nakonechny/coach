# 09 Production Scenarios

Live-incident diagnosis: something is broken or degraded in production, and the
candidate has to reason through where to look and what to change.

- Your Spring Boot API suddenly becomes slow in production — how would you find the bottleneck?
- Your database connection pool is exhausted — what would you check first?
- A microservice calls another service that takes 10 seconds to respond — how would you prevent your application from getting stuck?
- The same payment request reaches your API twice — how would you make the API idempotent?
- Your Kafka consumer is slower than the producer and lag keeps increasing — how would you troubleshoot it?
- Your Spring Boot application throws OutOfMemoryError only in production — how would you investigate it?
- A microservice goes down while processing an order — how should the system handle the failure?
- Your API works locally but returns errors after deployment — what would you investigate?
- Multiple instances of the same microservice execute the same scheduled job — how would you prevent duplicate execution?
- Your database query is fast, but the API response takes 2 seconds — where would you look?
- Traffic suddenly increases 10x across your microservices — what would you change to keep the system stable?
- Latency jumps from 100ms to 5s with no deployment — what would you check first?
- Your API works in staging but fails in production — how would you troubleshoot it?
- A service must handle 100K requests per second — how would you scale it?
