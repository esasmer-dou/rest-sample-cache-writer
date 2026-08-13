# rest-sample-cache-writer 0.6.2

`0.6.2` aligns the PostgreSQL-to-Redis writer with `rust-java-rest:4.4.0`,
`java-rust-cache:0.7.2`, and `rust-sample-model:0.4.1`.

- Scheduler, projection locks, TTL rules, database batching, and Redis write behavior are unchanged.
- The cache package carries the clean native runtime with REST ABI `28`, Redis ABI `6`, and
  Glowroot ABI `1`.
- This plain scheduler sample does not start telemetry by itself. A host runtime must explicitly
  start the micro agent before native Redis timings can be exported.

