# rest-sample-cache-writer 0.6.1

`0.6.1` aligns the PostgreSQL-to-Redis projection writer with `rust-java-rest:4.3.0`,
`java-rust-cache:0.7.1`, and `rust-sample-model:0.4.1`.

- Projection schedules, distributed locks, TTL validation, batching, and database behavior are
  unchanged.
- The writer continues to use the smallest cache-writer starter surface.
- The package uses the refreshed Windows/Linux native cache binaries.

Build and run:

```powershell
mvn clean verify
mvn exec:java
```
