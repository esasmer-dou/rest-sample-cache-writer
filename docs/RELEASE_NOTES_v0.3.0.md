# rest-sample-cache-writer v0.3.0

This sample aligns with `java-rust-cache:0.4.0`.

- Uses `reactor.cache.redis.access-mode=write-only`.
- Avoids allocating unused Redis read pools, permits, and topology state.
- Preserves projection-specific schedules, locks, TTL validation, and PostgreSQL batching.
- Refreshes the production/advanced overlays and Jlink workspace image.
