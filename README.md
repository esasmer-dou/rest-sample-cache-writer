# rest-sample-cache-writer

[English](README.md) | [Turkish](README.tr.md)

Minimal PostgreSQL-to-Redis cache writer sample for the Rust-Java ecosystem.

This process does not expose REST and does not use Dubbo. It reads PostgreSQL with ActiveJDBC + HikariCP, builds real-world nested JSON read models, and writes them to Redis through `java-rust-cache`, where Redis I/O is handled by Rust via JNI.

This sample is wired to `com.reactor:java-rust-cache:0.1.0-rc3`. The cache dependency includes the matching Windows/Linux native Redis bridge, so this writer can run without `rust-java-rest` and without a manual `java.library.path`.

## Maven Package Access

This sample pulls `java-rust-cache` from GitHub Packages. Add a GitHub token with `read:packages` access to your Maven `settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

Then set `GITHUB_PACKAGES_TOKEN` before running Maven.

## Real Scenario

Use this sample when a service owns PostgreSQL data but your REST pods should not query the database on every read.

Example flow:

1. `rest-sample-cache-writer` wakes up every `sample.writer.interval-ms`.
2. It takes a Redis lock with `sample.writer.lock-name`.
3. It reads customers from PostgreSQL in pages.
4. It publishes a new versioned Redis snapshot.
5. `rest-sample-cache-reader` serves the snapshot as REST JSON.

Multiple replicas are safe. Only one replica publishes because `VersionedJsonCacheWriter.refreshSnapshotWithLock(...)` owns the lock flow.

## What It Publishes

The writer creates these business projections:

| Projection | Redis abstraction | Real API consumer |
|---|---|---|
| Customer detail | `getById(id)` | Customer profile page |
| Customer number lookup | `getIndex("customer-no", customerNo)` | Call center quick lookup |
| Segment list | `getIndex("segment", segment)` | Pilot/enterprise customer list |
| Status list | `getIndex("status", status)` | Active/passive customer screen |
| Campaign candidates | `getIndex("campaign", "retention")` | Marketing candidate feed |
| Snapshot metadata | `getMeta()` | Operational health/debug |

The payload is intentionally nested: `customer`, `contact`, `profile`, `loyalty`, `risk`, `addresses`, `lastOrders`, and `audit`.

## Quick Start

Start temporary PostgreSQL and Redis:

```powershell
docker run -d --name rs-cache-postgres-test `
  -e POSTGRES_DB=reactor_sample `
  -e POSTGRES_USER=reactor `
  -e POSTGRES_PASSWORD=reactor `
  -p 15432:5432 postgres:15.7-alpine

docker run -d --name rs-cache-redis-test -p 16379:6379 redis:8.2.1-alpine3.22
```

Run one cache refresh:

```powershell
mvn -q clean package
mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
$cp = Get-Content target\cp.txt
java "-Dsample.writer.run-once=true" `
  "-Dsample.writer.initial-delay-ms=0" `
  "-Dreactor.cache.redis.port=16379" `
  -cp "target\classes;$cp" `
  com.reactor.sample.cache.writer.app.RestSampleCacheWriterApplication
```

Expected output:

```text
cache refresh published version=<version> keys=<count>
```

## Important Properties

| Property | Default | Use when |
|---|---:|---|
| `sample.writer.interval-ms` | `60000` | How often the writer refreshes Redis. Increase for low-change data. |
| `sample.writer.lock-ttl-ms` | `300000` | Prevents multiple replicas from publishing at the same time. Must be longer than a normal refresh. |
| `sample.writer.cache-ttl-ms` | `600000` | Redis data lifetime. Keep it longer than refresh interval. |
| `sample.writer.snapshot-batch-size` | `256` | Redis write batch size for versioned snapshot keys. Lower for memory-first pods; raise carefully if Redis write latency is low and refresh is too slow. |
| `sample.writer.page-size` | `500` | DB read batch size. Increase carefully if rows are small and DB is strong. |
| `sample.db.maximum-pool-size` | `2` | Writer is scheduled, not high-concurrency. Keep this low. |
| `reactor.cache.redis.write-connections` | `2` | Native Redis write connections. Increase only if refresh takes too long. |
| `reactor.cache.redis.max-write-inflight` | `4` | Backpressure for Redis writes. Keep bounded to protect memory. |

## Production Notes

- Keep schema migration outside this process in production.
- Do not put REST or Dubbo into this writer unless there is a hard product reason.
- Prefer precomputed JSON for read-heavy screens.
- Keep `sample.db.maximum-pool-size` low. This is a batch writer, not a request-serving app.
- If the same service is deployed with multiple replicas, keep Redis lock enabled.
