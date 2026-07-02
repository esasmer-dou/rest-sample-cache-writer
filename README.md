# rest-sample-cache-writer

[English](README.md) | [Turkish](README.tr.md)

Minimal PostgreSQL-to-Redis cache writer sample for the Rust-Java ecosystem.

This process does not expose REST and does not use Dubbo. It reads PostgreSQL with ActiveJDBC + HikariCP, builds real-world nested JSON read models, and writes them to Redis through `java-rust-cache`, where Redis I/O is handled by Rust via JNI.

This sample is wired to `com.reactor:java-rust-cache:0.2.0`. The cache dependency includes the matching Windows/Linux native Redis bridge, so this writer can run without `rust-java-rest` and without a manual `java.library.path`.

## Maven Package Access

This sample pulls `java-rust-cache` from GitHub Packages. Maven must authenticate before it can download the package; this is GitHub Packages' normal access model.

Add a GitHub token with `read:packages` access to your Maven `settings.xml`. Keep the `<id>` value exactly as shown, because it must match the repository id in this project's `pom.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

Then set `GITHUB_PACKAGES_TOKEN` before running Maven:

```powershell
$env:GITHUB_PACKAGES_TOKEN="YOUR_TOKEN_WITH_READ_PACKAGES"
mvn -q dependency:resolve
```

If Maven returns `401 Unauthorized`, check the token scope, environment variable, and server id matching first.

## Container Runtime Note

For minimal containers, set a writable native extract directory:

```bash
-Dreactor.cache.native.extract-dir=/tmp/java-rust-cache/native
```

The packaged Linux native binary is built on a manylinux2014/glibc 2.17 baseline. It is intended to run on common glibc-based images including CentOS 8, UBI 8/9, Ubuntu/Jammy, and Semeru/OpenJ9. If you use a custom native build, build it on the oldest Linux base your platform supports.

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

## Production Redis Topology

This writer must not depend on a single standalone Redis in production. If Redis is unavailable, the reader keeps serving the last valid version until TTL expires, but new snapshots cannot be published.

Use Sentinel when Redis has one writable primary and Sentinel owns failover:

```yaml
env:
  - name: REACTOR_CACHE_REDIS_TOPOLOGY
    value: "sentinel"
  - name: REACTOR_CACHE_REDIS_NODES
    value: "redis-sentinel-0:26379,redis-sentinel-1:26379,redis-sentinel-2:26379"
  - name: REACTOR_CACHE_REDIS_SENTINEL_MASTER_NAME
    value: "mymaster"
  - name: REACTOR_CACHE_REDIS_WRITE_CONNECTIONS
    value: "2"
  - name: REACTOR_CACHE_REDIS_MAX_WRITE_INFLIGHT
    value: "4"
```

Use Cluster when snapshot keys must be distributed across Redis nodes:

```yaml
env:
  - name: REACTOR_CACHE_REDIS_TOPOLOGY
    value: "cluster"
  - name: REACTOR_CACHE_REDIS_NODES
    value: "redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379"
  - name: REACTOR_CACHE_REDIS_CLUSTER_MAX_REDIRECTS
    value: "5"
  - name: REACTOR_CACHE_REDIS_TOPOLOGY_REFRESH_MS
    value: "30000"
```

For Cluster, keep `reactor.cache.redis.database=0`. `setMany` is cluster-safe: keys are grouped by destination node and redirects are handled. If a business projection needs same-slot locality, use hash tags in the key design.

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
| `reactor.cache.redis.topology` | `standalone` | Use `sentinel` or `cluster` for production HA/sharding. |
| `reactor.cache.redis.nodes` | empty | Sentinel node list or Cluster startup node list. |
| `reactor.cache.redis.sentinel.master-name` | empty | Required only for Sentinel. |

## Production Notes

- Keep schema migration outside this process in production.
- Do not put REST or Dubbo into this writer unless there is a hard product reason.
- Prefer precomputed JSON for read-heavy screens.
- Keep `sample.db.maximum-pool-size` low. This is a batch writer, not a request-serving app.
- If the same service is deployed with multiple replicas, keep Redis lock enabled.
