# rest-sample-cache-writer v0.3.1

This sample aligns with `java-rust-cache:0.4.1`.

- Uses the current `24/7/6` packaged native runtime.
- Keeps Redis in `write-only` mode, so unused native read pools are not allocated.
- Keeps projection schedules, locks, TTL validation, PostgreSQL batching, and materialization logic
  unchanged.
- This is a dependency and native provenance refresh; application code does not change.
