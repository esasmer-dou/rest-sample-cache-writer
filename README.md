# rest-sample-cache-writer

[English](https://github.com/esasmer-dou/rest-sample-cache-writer/blob/master/README.md) | [Turkish](https://github.com/esasmer-dou/rest-sample-cache-writer/blob/master/README.tr.md)

Minimal PostgreSQL-to-Redis cache writer sample for the Rust-Java ecosystem.

This process does one job. It reads PostgreSQL and writes Redis.

It does not expose REST. It does not use Dubbo. Java builds the read model. Rust writes Redis through `java-rust-cache`.

This sample uses `com.reactor:java-rust-cache:0.2.4`. The package already includes Windows and Linux native binaries.

Shared row model records come from `com.reactor.sample:sample-model:0.1.0`. The writer keeps DB and
projection logic locally, but it does not duplicate the customer row model used by other samples.

## Property Layers

The default `src/main/resources/rest-sample-cache-writer.properties` is the minimum local file. It
contains only the DB connection, projection list, base TTL/lock settings, and local Redis address.

Use overlays when you need more:

```powershell
java "-Dreactor.config.file=src/main/resources/config/production.properties" ...
```

Use both production and advanced tuning only after measuring DB wait, Redis latency and RSS:

```powershell
java "-Dreactor.config.file=src/main/resources/config/production.properties;src/main/resources/config/advanced-tuning.properties" ...
```

- `config/production.properties`: Kubernetes/production-safe DB, Redis and TTL defaults.
- `config/advanced-tuning.properties`: projection-specific locks, larger batches and Redis pipeline tuning.
- Environment alternative: `REACTOR_CONFIG_FILE=/app/config/production.properties`.

## Contents

1. [Copy-Paste: Publish A Customer Cache Snapshot](#copy-paste-publish-a-customer-cache-snapshot)
2. [Maven Package Access](#maven-package-access)
3. [Real Scenario](#real-scenario)
4. [What It Publishes](#what-it-publishes)
5. [Declarative Projection Config](#declarative-projection-config)
6. [TTL Model](#ttl-model)
7. [TTL Recipes By Scenario](#ttl-recipes-by-scenario)
8. [Production Redis Topology](#production-redis-topology)
9. [Important Properties](#important-properties)
10. [Glossary](#glossary)
11. [Production Notes](#production-notes)

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
cache refresh published projection=detail version=<version> keys=<count>
cache refresh published projection=campaign version=<version> keys=<count>
```

After this, `rest-sample-cache-reader` can serve customer profiles, segment lists, and campaign
candidates from the same Redis instance.

## Maven Package Access

This sample pulls `java-rust-cache` and `sample-model` from GitHub Packages. Maven must authenticate before it can download the packages; this is GitHub Packages' normal access model.

Add a GitHub token with `read:packages` access to your Maven `settings.xml`. Keep the `<id>` value exactly as shown, because it must match the repository id in this project's `pom.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
  <server>
    <id>github-sample-model</id>
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

1. `rest-sample-cache-writer` starts one scheduled job per projection.
2. Each projection has its own interval.
3. Each projection has its own Redis lock.
4. The lock owner reads PostgreSQL and publishes that projection.
5. `rest-sample-cache-reader` serves the snapshot as REST JSON.

Multiple replicas are safe. Two replicas can publish different projections at the same time. The same projection is still protected by one Redis lock.

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

## Declarative Projection Config

Projection config is resolved by `java-rust-cache`:

```java
List<CacheWriterProjectionSettings> projections =
        CacheWriterProjectionSettings.resolveAll(properties, "sample.writer");
```

The library resolves:

- active projection names from `sample.writer.projections`
- namespace per projection
- refresh interval per projection
- Redis lock name per projection
- configured TTL and safe effective TTL

The application still owns the business logic:

```java
switch (projection.name()) {
    case "detail" -> publishCustomerDetails();
    case "campaign" -> publishCampaignCandidates();
    default -> throw new IllegalArgumentException("Unknown projection: " + projection.name());
}
```

BEST: let the library parse config and keep DB queries and JSON shape explicit. ANTI-PATTERN:
reflection-based projection magic that guesses what to publish from class names.

### What The Library Now Owns

The sample no longer carries its own scheduler implementation or JSON escaping utility.

```java
ProjectionRefreshScheduler scheduler = ProjectionRefreshScheduler.builder()
    .settings(projectionSettings)
    .refresher(materializer::refreshProjection)
    .schedulerThreads(properties.getInt("sample.writer.scheduler-threads"))
    .runOnce(properties.getBoolean("sample.writer.run-once"))
    .threadNamePrefix("activejdbc-cache-writer")
    .build();
```

`CustomerJsonWriter` extends `JsonWriter`, so escaping, UTF-8 conversion, primitive fields, and array
helpers come from the library. The customer JSON shape still stays in the sample:

```java
public final class CustomerJsonWriter extends JsonWriter {
    public byte[] customerDetail(SampleCustomer customer) {
        StringBuilder json = json(768);
        // explicit customer fields stay here
        return utf8(json);
    }
}
```

`PostgresCustomerRepository` extends `JdbcRepository`. The library owns connection/query/lifecycle
boilerplate. The sample still owns SQL, indexes, and row mapping.

## TTL Model

The writer supports two TTL levels:

```properties
sample.writer.projections=detail,segment,status,campaign,meta
sample.writer.cache-ttl-ms=600000
sample.writer.detail.cache-ttl-ms=1800000
sample.writer.segment.cache-ttl-ms=300000
sample.writer.status.cache-ttl-ms=300000
sample.writer.campaign.cache-ttl-ms=120000
sample.writer.meta.cache-ttl-ms=300000
```

Use the base `sample.writer.cache-ttl-ms` when all projections can live for the same period. Use projection TTLs when data freshness is different. For example, customer detail can live longer, but campaign candidates may need a shorter TTL.

`sample.writer.projections` controls which projection jobs are created by the scheduler. If you only need customer detail and campaign candidates, use:

```properties
sample.writer.projections=detail,campaign
```

Then the writer publishes only `detail` and `campaign`. Adding a brand-new business projection still needs DB read and JSON writer code. This is intentional. Config selects the active projection set; business transformation logic stays explicit in code.

Do not put different TTLs on random keys inside the same snapshot. That creates partial snapshots. This sample uses separate namespaces instead, so each projection stays internally consistent.

**Production warning:** For each projection, cache TTL must be greater than that projection's refresh interval. If this is misconfigured, the application does not stop. The writer stays safe and raises the effective TTL to `interval + sample.writer.cache-ttl-safety-margin-ms`. The default safety margin is `30000` ms. This is a recovery behavior; the correct fix is still to correct the property value.

Bad config example:

```properties
sample.writer.campaign.interval-ms=30000
sample.writer.campaign.cache-ttl-ms=10000
sample.writer.cache-ttl-safety-margin-ms=30000
```

In this case, the writer uses `60000` ms as the runtime `campaign` TTL and logs the warning at startup and on every refresh attempt:

```text
WARNING cache writer config projection=campaign property=sample.writer.campaign.cache-ttl-ms configuredCacheTtlMs=10000 intervalMs=30000 effectiveCacheTtlMs=60000 safetyMarginMs=30000 reason=cache-ttl-ms-must-be-greater-than-interval phase=startup
```

## TTL Recipes By Scenario

Use these examples as starting points. Keep every data TTL longer than that projection's refresh interval. If TTL is shorter than the interval, readers can see expired projections before the next publish finishes.

### Scenario: Stable Customer Profile, Fresh Campaign Feed

Use this when profile pages change slowly, but campaign eligibility changes often. The REST reader keeps serving customer details for longer, while campaign data expires faster.

```properties
sample.writer.interval-ms=60000
sample.writer.detail.interval-ms=300000
sample.writer.segment.interval-ms=120000
sample.writer.status.interval-ms=120000
sample.writer.campaign.interval-ms=30000
sample.writer.meta.interval-ms=60000
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

The same rule applies to lock names. If you pass `-Dsample.writer.lock-name=crm.customer.prod.refresh`, the writer derives `crm.customer.prod.refresh.detail`, `crm.customer.prod.refresh.segment`, and the other projection locks.

### Scenario: High-Traffic Campaign Screen

Use this when a campaign endpoint receives a lot of traffic and must reflect frequent rule changes. Keep campaign TTL short, but keep detail TTL longer to avoid unnecessary Redis churn.

```yaml
env:
  - name: SAMPLE_WRITER_INTERVAL_MS
    value: "30000"
  - name: SAMPLE_WRITER_LOCK_TTL_MS
    value: "120000"
  - name: SAMPLE_WRITER_SCHEDULER_THREADS
    value: "2"
  - name: SAMPLE_WRITER_DETAIL_INTERVAL_MS
    value: "300000"
  - name: SAMPLE_WRITER_SEGMENT_INTERVAL_MS
    value: "120000"
  - name: SAMPLE_WRITER_STATUS_INTERVAL_MS
    value: "120000"
  - name: SAMPLE_WRITER_CAMPAIGN_INTERVAL_MS
    value: "30000"
  - name: SAMPLE_WRITER_META_INTERVAL_MS
    value: "60000"
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
sample.writer.detail.interval-ms=300000
sample.writer.segment.interval-ms=120000
sample.writer.status.interval-ms=120000
sample.writer.campaign.interval-ms=60000
sample.writer.meta.interval-ms=120000
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
cache refresh published projection=detail version=<version> keys=<count>
cache refresh published projection=campaign version=<version> keys=<count>
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

| Property | Default | What it does | When to change |
|---|---:|---|---|
| `sample.writer.interval-ms` | `60000` | Base refresh interval. Projection interval overrides it. | Use as a shared default. |
| `sample.writer.lock-name` | `crm.customer.refresh` | Base lock name. Projection locks are derived from it unless explicitly set. | Change per cache domain. Keep all replicas aligned. |
| `sample.writer.lock-ttl-ms` | `300000` | Base lock TTL. Projection lock TTL overrides it. | Set longer than that projection's normal refresh duration. |
| `sample.writer.scheduler-threads` | `2` | Number of local projection scheduler threads. | Keep low for small pods. Raise only if one writer replica must run projections in parallel. |
| `sample.writer.projections` | `detail,segment,status,campaign,meta` | Selects active projection jobs. | Narrow it when this writer should publish only selected read models. |
| `sample.writer.cache-ttl-ms` | `600000` | Base Redis data lifetime. | Use when all projections can share one TTL. |
| `sample.writer.cache-ttl-safety-margin-ms` | `30000` | Safety margin added above interval when a TTL is misconfigured too short. | Usually leave unchanged. Lower only with measured low-risk short-interval projections. |
| `sample.writer.detail.interval-ms` | `300000` | Refresh interval for customer detail. | Increase for stable profile data. |
| `sample.writer.detail.lock-name` | `crm.customer.detail.refresh` | Redis lock for detail refresh. | Change only with all writer replicas. |
| `sample.writer.detail.lock-ttl-ms` | `300000` | Detail lock lifetime. | Keep longer than detail refresh time. |
| `sample.writer.detail.namespace` | `crm.customer.detail` | Stores customer detail and customer-number lookup. | Change with the matching reader namespace. |
| `sample.writer.detail.cache-ttl-ms` | `1800000` | Sets customer detail lifetime. | Raise for stable profile data. Lower for stricter freshness. |
| `sample.writer.segment.interval-ms` | `120000` | Refresh interval for segment lists. | Lower if segment membership changes often. |
| `sample.writer.segment.lock-name` | `crm.customer.segment.refresh` | Redis lock for segment refresh. | Change only with all writer replicas. |
| `sample.writer.segment.lock-ttl-ms` | `120000` | Segment lock lifetime. | Keep longer than segment refresh time. |
| `sample.writer.segment.namespace` | `crm.customer.segment` | Stores segment list projections. | Change only with reader config. |
| `sample.writer.segment.cache-ttl-ms` | `300000` | Sets segment list lifetime. | Lower when segment membership changes often. |
| `sample.writer.status.interval-ms` | `120000` | Refresh interval for status lists. | Lower when active/passive status changes often. |
| `sample.writer.status.lock-name` | `crm.customer.status.refresh` | Redis lock for status refresh. | Change only with all writer replicas. |
| `sample.writer.status.lock-ttl-ms` | `120000` | Status lock lifetime. | Keep longer than status refresh time. |
| `sample.writer.status.namespace` | `crm.customer.status` | Stores status list projections. | Change only with reader config. |
| `sample.writer.status.cache-ttl-ms` | `300000` | Sets status list lifetime. | Lower when active/passive status changes often. |
| `sample.writer.campaign.interval-ms` | `30000` | Refresh interval for campaign candidates. | Lower for fresher campaign feeds. |
| `sample.writer.campaign.lock-name` | `crm.customer.campaign.refresh` | Redis lock for campaign refresh. | Change only with all writer replicas. |
| `sample.writer.campaign.lock-ttl-ms` | `120000` | Campaign lock lifetime. | Keep longer than campaign refresh time. |
| `sample.writer.campaign.namespace` | `crm.customer.campaign` | Stores campaign candidate projections. | Change only with reader config. |
| `sample.writer.campaign.cache-ttl-ms` | `120000` | Sets campaign candidate lifetime. | Keep short when campaign rules change often. |
| `sample.writer.meta.interval-ms` | `60000` | Refresh interval for metadata. | Keep close to operational visibility needs. |
| `sample.writer.meta.lock-name` | `crm.customer.meta.refresh` | Redis lock for metadata refresh. | Change only with all writer replicas. |
| `sample.writer.meta.lock-ttl-ms` | `120000` | Metadata lock lifetime. | Keep longer than metadata refresh time. |
| `sample.writer.meta.namespace` | `crm.customer.meta` | Stores snapshot metadata. | Change only with reader config. |
| `sample.writer.meta.cache-ttl-ms` | `300000` | Sets metadata lifetime. | Keep close to the shortest business projection TTL. |
| `sample.writer.snapshot-batch-size` | `256` | Controls Redis batch write size. | Lower for small memory pods. Raise only if refresh is too slow. |
| `sample.writer.page-size` | `500` | Controls DB read page size. | Raise for small rows and strong DB. Lower for low memory. |
| `sample.db.maximum-pool-size` | `2` | Caps DB connections. | Keep low. This is a scheduled writer. |
| `reactor.cache.redis.write-connections` | `2` | Opens native Redis write connections. | Increase only when Redis write latency is the bottleneck. |
| `reactor.cache.redis.max-write-inflight` | `4` | Bounds concurrent Redis writes. | Keep bounded to protect memory. |
| `reactor.cache.redis.topology` | `standalone` | Selects Redis mode. | Use `sentinel` or `cluster` in production. |
| `reactor.cache.redis.nodes` | empty | Lists Sentinel or Cluster nodes. | Set when topology is `sentinel` or `cluster`. |
| `reactor.cache.redis.sentinel.master-name` | empty | Names the Sentinel master. | Required for Sentinel. |
| `reactor.cache.redis.sentinel.master-check-ms` | `1000` | Checks Sentinel master changes. | Lower only if measured failover recovery is too slow. |

## Glossary

| Term | Meaning |
|---|---|
| TTL | Time to live. Redis deletes the key after this time. |
| Projection | A ready read model for one use case, such as customer detail or campaign candidates. |
| Namespace | Redis key prefix for one projection family. |
| Snapshot | A complete published version of one projection. |
| Current version | The Redis pointer that tells the reader which snapshot is active. |
| Cache miss | Redis does not have the requested data. The reader returns a controlled not-found response. |
| Projection lock | Redis lock that protects one projection refresh. Different projections can run at the same time. |
| Backpressure | A hard limit that prevents queues and memory from growing without control. |
| Sentinel | Redis high-availability mode with automatic primary failover. |
| Cluster | Redis sharding mode. Data is split across nodes. |

## Production Notes

- Keep schema migration outside this process in production.
- The sample now reads PostgreSQL with keyset pagination (`id > lastSeenId`) instead of `offset`. Keep that pattern for large tables; offset pages get slower as the table grows.
- Do not put REST or Dubbo into this writer unless there is a hard product reason.
- Prefer precomputed JSON for read-heavy screens.
- Keep `sample.db.maximum-pool-size` low. This is a batch writer, not a request-serving app.
- If the same service is deployed with multiple replicas, keep projection Redis locks aligned across replicas.
