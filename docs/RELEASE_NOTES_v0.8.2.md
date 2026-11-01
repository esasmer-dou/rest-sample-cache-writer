# rest-sample-cache-writer 0.8.2

[English](RELEASE_NOTES_v0.8.2.md) | [Türkçe](RELEASE_NOTES_v0.8.2.tr.md)

Coordinated update: November 1, 2026.

## Dependencies

Keep platform parent `com.reactor:rust-java-platform-parent:4.8.2`, which aligns
cache `0.9.2`. Entries for the existing POM, retaining PostgreSQL/Hikari and plugins:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-cache-writer</artifactId>
  <version>4.8.2</version>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-jdbc</artifactId>
  <version>4.8.2</version>
</dependency>
<dependency>
  <groupId>com.reactor.sample</groupId>
  <artifactId>rust-sample-model</artifactId>
  <version>0.5.2</version>
</dependency>
```

See [Maven access](../README.md#maven-package-access). Rebuild with
`mvn -B -ntp -DskipTests clean package`; this is not a test result.

## Existing Flow And Upgrade

The writer still owns PostgreSQL-to-Redis publication, bounded JDBC work,
projection schedules, locks, fencing and TTL safety. It exposes no HTTP API.
Prepared JSON field names do not remove business mapping or change the dataset.
The reader's `CacheResponses.reply`/optional L1 path is downstream, not a writer API.
The [Compose recipe](../README.md#compose) exercises the shared topology; the
catalog-only native overlay changes DB rows through explicit SQL, not customer RPC.

No JNI ABI, projection namespace or configuration-key migration is required.
Use the packaged native library from the aligned dependencies; do not copy old SO/DLLs.
Publication and full qualification require the exact candidate's receipt.
No new throughput, p99, memory or soak PASS is asserted here; hosted compatibility
is separate from EmaTech performance/soak evidence.
