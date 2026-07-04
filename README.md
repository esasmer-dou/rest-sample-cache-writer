# rest-sample-cache-writer

[English](README.md) | [Turkish](README.tr.md)

Minimal PostgreSQL-to-Redis cache writer sample for the Rust-Java ecosystem.

This process does not expose REST and does not use Dubbo. It reads PostgreSQL with ActiveJDBC + HikariCP, builds real-world nested JSON read models, and writes them to Redis through `java-rust-cache`, where Redis I/O is handled by Rust via JNI.

This sample is wired to `com.reactor:java-rust-cache:0.2.1`. The cache dependency includes the matching Windows/Linux native Redis bridge, so this writer can run without `rust-java-rest` and without a manual `java.library.path`.

## Copy-Paste: Publish A Customer Cache Snapshot

In this scenario PostgreSQL is the source of truth. Redis stores the read model. The writer reads
from the database and publishes ready JSON snapshots to Redis. It does not expose REST endpoints.

Run these commands from the `rest-sample-cache-writer` directory:

```powershell
docker rm -f rs-cache-postgres-test rs-cache-redis-test 2>$null

docker run -d --name rs-cache-postgres-test `
  -e POSTGRES_DB=reactor_sample `
  -e POSTGRES_USER=reactor `
  -e POSTGRES_PASSWORD=reactor `
  -p 15432:5432 postgres:15.7-alpine

docker run -d --name rs-cache-redis-test `
  -p 16379:6379 redis:8.2.1-alpine3.22

$env:GITHUB_PACKAGES_TOKEN="YOUR_TOKEN_WITH_READ_PACKAGES"
mvn -q clean package
mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"

$cp = Get-Content target\cp.txt
java "-Dsample.writer.run-once=true" `
  "-Dsample.writer.initial-delay-ms=0" `
  "-Dsample.db.jdbc-url=jdbc:postgresql://127.0.0.1:15432/reactor_sample" `
  "-Dsample.db.username=reactor" `
  "-Dsample.db.password=reactor" `
  "-Dreactor.cache.redis.host=127.0.0.1" `
  "-Dreactor.cache.redis.port=16379" `
  -cp "target\classes;$cp" `
  com.reactor.sample.cache.writer.app.RestSampleCacheWriterApplication
```

Expected output:

```text
cache refresh published versions=detail=<version>,segment=<version>,status=<version>,campaign=<version>,meta=<version> keys=<count>
```

After this, `rest-sample-cache-reader` can serve customer profiles, segment lists, and campaign
candidates from the same Redis instance.

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
4. It publishes separate versioned Redis snapshots for detail, segment, status, campaign, and metadata.
5. `rest-sample-cache-reader` serves the snapshot as REST JSON.

Multiple replicas are safe. Only one replica publishes because the writer keeps a single Redis lock around the projection refresh flow.

## What It Publishes

The writer creates these business projections:

| Projection | Namespace | Default TTL | Redis abstraction | Real API consumer |
|---|---|---:|---|---|
| Customer detail | `crm.customer.detail` | `1800000` | `getById(id)` and `getIndex("customer-no", customerNo)` | Customer profile and call center lookup |
| Segment list | `crm.customer.segment` | `300000` | `getIndex("segment", segment)` | Pilot/enterprise customer list |
| Status list | `crm.customer.status` | `300000` | `getIndex("status", status)` | Active/passive customer screen |
| Campaign candidates | `crm.customer.campaign` | `120000` | `getIndex("campaign", "retention")` | Marketing candidate feed |
| Snapshot metadata | `crm.customer.meta` | `300000` | `getMeta()` | Operational health/debug |

The payload is intentionally nested: `customer`, `contact`, `profile`, `loyalty`, `risk`, `addresses`, `lastOrders`, and `audit`.

## TTL Model

The writer supports two TTL levels:

```properties
sample.writer.cache-ttl-ms=600000
sample.writer.detail.cache-ttl-ms=1800000
sample.writer.segment.cache-ttl-ms=300000
sample.writer.status.cache-ttl-ms=300000
sample.writer.campaign.cache-ttl-ms=120000
sample.writer.meta.cache-ttl-ms=300000
```

Use the base `sample.writer.cache-ttl-ms` when all projections can live for the same period. Use projection TTLs when data freshness is different. For example, customer detail can live longer, but campaign candidates may need a shorter TTL.

Do not put different TTLs on random keys inside the same snapshot. That creates partial snapshots. This sample uses separate namespaces instead, so each projection stays internally consistent.

## TTL Recipes By Scenario

Use these examples as starting points. Keep every data TTL longer than the writer interval. If TTL is shorter than the refresh interval, readers can see expired projections before the next publish finishes.

### Scenario: Stable Customer Profile, Fresh Campaign Feed

Use this when profile pages change slowly, but campaign eligibility changes often. The REST reader keeps serving customer details for longer, while campaign data expires faster.

```properties
sample.writer.interval-ms=60000
sample.writer.cache-ttl-ms=600000
sample.writer.detail.cache-ttl-ms=1800000
sample.writer.segment.cache-ttl-ms=300000
sample.writer.status.cache-ttl-ms=300000
sample.writer.campaign.cache-ttl-ms=120000
sample.writer.meta.cache-ttl-ms=300000
```

Operational effect: profile endpoints tolerate a missed writer run better. Campaign endpoints refresh quickly and expire sooner if the writer stops.

### Scenario: One Freshness Window For All Data

Use this when all projections are produced from the same source and the same freshness window is acceptable. This is simpler for first production rollout.

```powershell
java "-Dsample.writer.namespace=crm.customer.prod" `
  "-Dsample.writer.cache-ttl-ms=900000" `
  "-Dsample.writer.interval-ms=300000" `
  -cp "target\classes;$cp" `
  com.reactor.sample.cache.writer.app.RestSampleCacheWriterApplication
```

Runtime base override wins over file defaults. The writer derives these namespaces automatically: `crm.customer.prod.detail`, `crm.customer.prod.segment`, `crm.customer.prod.status`, `crm.customer.prod.campaign`, and `crm.customer.prod.meta`.

### Scenario: High-Traffic Campaign Screen

Use this when a campaign endpoint receives a lot of traffic and must reflect frequent rule changes. Keep campaign TTL short, but keep detail TTL longer to avoid unnecessary Redis churn.

```yaml
env:
  - name: SAMPLE_WRITER_INTERVAL_MS
    value: "30000"
  - name: SAMPLE_WRITER_LOCK_TTL_MS
    value: "120000"
  - name: SAMPLE_WRITER_DETAIL_CACHE_TTL_MS
    value: "1800000"
  - name: SAMPLE_WRITER_SEGMENT_CACHE_TTL_MS
    value: "180000"
  - name: SAMPLE_WRITER_STATUS_CACHE_TTL_MS
    value: "180000"
  - name: SAMPLE_WRITER_CAMPAIGN_CACHE_TTL_MS
    value: "45000"
  - name: SAMPLE_WRITER_META_CACHE_TTL_MS
    value: "60000"
```

Operational effect: campaign freshness improves, but writer runs more often. Watch DB query time, Redis write latency, and `reactor.cache.redis.max-write-inflight`.

### Scenario: Memory-Sensitive Redis

Use this when Redis memory is tight and stale read models should disappear quickly. This is valid for low-traffic back-office screens, not for critical always-on customer APIs.

```properties
sample.writer.interval-ms=120000
sample.writer.detail.cache-ttl-ms=360000
sample.writer.segment.cache-ttl-ms=240000
sample.writer.status.cache-ttl-ms=240000
sample.writer.campaign.cache-ttl-ms=180000
sample.writer.meta.cache-ttl-ms=240000
```

Operational effect: Redis retains fewer old keys. The trade-off is lower tolerance for writer outages. If the writer is stopped longer than the TTL, reader endpoints correctly return cache-miss responses.

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
cache refresh published versions=detail=<version>,segment=<version>,status=<version>,campaign=<version>,meta=<version> keys=<count>
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
| `sample.writer.cache-ttl-ms` | `600000` | Base Redis data lifetime. Runtime override applies to all projections unless a projection-specific TTL is also set. |
| `sample.writer.detail.namespace` | `crm.customer.detail` | Customer detail and customer-number lookup namespace. Reader must use the same value. |
| `sample.writer.detail.cache-ttl-ms` | `1800000` | Detail payload lifetime. Raise if customer profile changes slowly. |
| `sample.writer.segment.namespace` | `crm.customer.segment` | Segment list namespace. |
| `sample.writer.segment.cache-ttl-ms` | `300000` | Segment list lifetime. Lower when segment membership changes often. |
| `sample.writer.status.namespace` | `crm.customer.status` | Status list namespace. |
| `sample.writer.status.cache-ttl-ms` | `300000` | Status list lifetime. |
| `sample.writer.campaign.namespace` | `crm.customer.campaign` | Campaign candidate namespace. |
| `sample.writer.campaign.cache-ttl-ms` | `120000` | Campaign candidate lifetime. Keep short when eligibility changes often. |
| `sample.writer.meta.namespace` | `crm.customer.meta` | Metadata namespace. |
| `sample.writer.meta.cache-ttl-ms` | `300000` | Metadata lifetime. |
| `sample.writer.snapshot-batch-size` | `256` | Redis write batch size for versioned snapshot keys. Lower for memory-first pods; raise carefully if Redis write latency is low and refresh is too slow. |
| `sample.writer.page-size` | `500` | DB read batch size. Increase carefully if rows are small and DB is strong. |
| `sample.db.maximum-pool-size` | `2` | Writer is scheduled, not high-concurrency. Keep this low. |
| `reactor.cache.redis.write-connections` | `2` | Native Redis write connections. Increase only if refresh takes too long. |
| `reactor.cache.redis.max-write-inflight` | `4` | Backpressure for Redis writes. Keep bounded to protect memory. |
| `reactor.cache.redis.topology` | `standalone` | Use `sentinel` or `cluster` for production HA/sharding. |
| `reactor.cache.redis.nodes` | empty | Sentinel node list or Cluster startup node list. |
| `reactor.cache.redis.sentinel.master-name` | empty | Required only for Sentinel. |
| `reactor.cache.redis.sentinel.master-check-ms` | `1000` | How often Sentinel mode checks whether the master changed after failover. Lower only when recovery-time evidence needs it. |

## Production Notes

- Keep schema migration outside this process in production.
- The sample now reads PostgreSQL with keyset pagination (`id > lastSeenId`) instead of `offset`. Keep that pattern for large tables; offset pages get slower as the table grows.
- Do not put REST or Dubbo into this writer unless there is a hard product reason.
- Prefer precomputed JSON for read-heavy screens.
- Keep `sample.db.maximum-pool-size` low. This is a batch writer, not a request-serving app.
- If the same service is deployed with multiple replicas, keep Redis lock enabled.
