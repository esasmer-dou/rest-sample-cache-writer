# rest-sample-cache-writer

[English](README.md) | [Türkçe](README.tr.md)

A small scheduled application that reads PostgreSQL and publishes ready JSON snapshots to Redis.

- Java owns SQL, business mapping, and scheduling decisions.
- Rust owns Redis I/O through `java-rust-cache`.
- The process does not expose a REST API.
- The process does not use Dubbo.
- Each projection has its own schedule, TTL, and distributed lock.

Current versions: `java-rust-cache:0.7.0`, `rust-sample-model:0.4.0`.

The POM uses `rust-java-platform-parent` and one `rust-java-starter-cache-writer` dependency. This
starter intentionally does not pull the REST runtime into the scheduled writer process. Code
generators stay on the compiler path and are not packaged as production classes.

## What 0.6.0 Simplifies

- The managed writer launcher creates and closes the PostgreSQL resource in one lifecycle.
- Generated projection registry wiring replaces repeated projection lookup code.
- SQL queries and JSON business mapping remain explicit Java code.
- Projection names, refresh intervals, TTL rules, locks, and Redis keys are unchanged.

## Declarative Flow

| You write | Generated or managed for you | Not created in this process |
| --- | --- | --- |
| SQL and batch repository | Repository lifecycle and shutdown order | REST server |
| Projection materializer | Projection registry and schedules | Redis read pool |
| JSON business shape | Bounded publish pipeline | Dubbo runtime |
| Per-projection interval, TTL, and lock | Scheduler and fenced lock ownership | Duplicate schedule wiring |

The framework removes lifecycle and registry boilerplate. SQL, data selection, and JSON business
shape remain explicit Java code so the cache contract stays reviewable.

## Start Here

Use this sample when REST pods should read prepared data instead of querying PostgreSQL on every request.

A projection is a cache view prepared for one endpoint group, such as customer detail or campaign candidates.

```text
PostgreSQL -> this writer -> Redis -> cache reader -> HTTP client
```

## Quick Start

### 1. Start PostgreSQL and Redis

Run from PowerShell:

```powershell
docker rm -f rs-cache-postgres-test rs-cache-redis-test 2>$null

docker run -d --name rs-cache-postgres-test `
  -e POSTGRES_DB=reactor_sample `
  -e POSTGRES_USER=reactor `
  -e POSTGRES_PASSWORD=reactor `
  -p 15432:5432 postgres:15.7-alpine

docker run -d --name rs-cache-redis-test `
  -p 16379:6379 redis:8.2.1-alpine3.22
```

### 2. Publish one snapshot

Run from this repository:

```powershell
$env:GITHUB_PACKAGES_TOKEN="YOUR_TOKEN_WITH_READ_PACKAGES"

mvn -q `
  "-Dsample.writer.run-once=true" `
  "-Dsample.writer.initial-delay-ms=0" `
  "-Dsample.db.jdbc-url=jdbc:postgresql://127.0.0.1:15432/reactor_sample" `
  "-Dsample.db.username=reactor" `
  "-Dsample.db.password=reactor" `
  "-Dreactor.cache.redis.host=127.0.0.1" `
  "-Dreactor.cache.redis.port=16379" `
  clean compile exec:java
```

Expected log:

```text
cache refresh published projection=detail version=<version> keys=<count>
```

### 3. Read the data

Start [`rest-sample-cache-reader`](https://github.com/esasmer-dou/rest-sample-cache-reader). The reader uses the same Redis instance.

## What Is Published

| Projection | Default namespace | Typical use |
|---|---|---|
| `detail` | `crm.customer.detail` | Customer profile |
| `segment` | `crm.customer.segment` | Customers in a segment |
| `status` | `crm.customer.status` | Active or passive customer list |
| `campaign` | `crm.customer.campaign` | Campaign candidates |
| `meta` | `crm.customer.meta` | Snapshot status and version |

Activate only the projections you need:

```properties
sample.writer.projections=detail,segment,status,campaign,meta
```

For example, a campaign-only process can use:

```properties
sample.writer.projections=campaign,meta
```

## Schedule, TTL, and Lock

Every projection is independent.

```properties
sample.writer.campaign.interval-ms=30000
sample.writer.campaign.cache-ttl-ms=120000
sample.writer.campaign.lock-name=crm.customer.campaign.refresh
sample.writer.campaign.lock-ttl-ms=120000
```

This means:

1. The campaign projection runs every 30 seconds.
2. The published data expires after 120 seconds.
3. Only one replica refreshes the campaign projection at a time.
4. Another replica may refresh a different projection at the same time.

Keep cache TTL longer than the refresh interval. The library corrects an unsafe value and logs the effective TTL, but production configuration should still be explicit.

## Redis Topology

| Environment | Set |
|---|---|
| Local Redis | `reactor.cache.redis.topology=standalone` |
| Redis Sentinel | `reactor.cache.redis.topology=sentinel` plus Sentinel nodes and master name |
| Redis Cluster | `reactor.cache.redis.topology=cluster` plus cluster nodes |

The writer is intentionally write-only:

```properties
reactor.cache.redis.access-mode=write-only
```

## Configuration

The application reads configuration in this order:

1. `src/main/resources/rest-sample-cache-writer.properties`
2. Files passed through `reactor.config.file` or `REACTOR_CONFIG_FILE`
3. JVM `-D...` values and supported environment variables

| File | Purpose |
|---|---|
| `rest-sample-cache-writer.properties` | Local DB, scheduler, projection, lock, and Redis defaults |
| `config/production.properties` | Production HikariCP limits, TTLs, and Redis timeouts |
| `config/advanced-tuning.properties` | Larger batches, projection-specific locks, and Redis pipeline tuning |

Use the production overlay:

```powershell
java "-Dreactor.config.file=src/main/resources/config/production.properties" ...
```

Important starting values:

| Property | Start with | Change when |
|---|---:|---|
| `sample.db.maximum-pool-size` | `2` | DB wait is high and PostgreSQL has capacity |
| `sample.writer.scheduler-threads` | `2` | Independent projections cannot finish on time |
| `sample.writer.page-size` | `500` | Measure DB latency and heap before increasing |
| `reactor.cache.redis.write-connections` | `2` | Redis write queue is the measured bottleneck |
| `reactor.cache.redis.max-write-inflight` | `4` | Increase only with Redis capacity evidence |

Do not increase all limits together. That hides the real bottleneck and increases RSS.

## Multiple Replicas

Multiple replicas are supported.

- The lock key belongs to a projection.
- Two replicas cannot publish the same projection together.
- Two replicas can publish different projections together.
- If one replica stops, another replica acquires the lock after its TTL.

Use a different `lock-name` for every projection.

## Code Map

| File | Why it matters |
|---|---|
| `RestSampleCacheWriterApplication.java` | Starts the projection scheduler |
| `CustomerCacheMaterializer.java` | Implements the projection refresher; generated registry connects each projection |
| `PostgresCustomerRepository.java` | Owns SQL and database paging |
| `CustomerJsonWriter.java` | Writes JSON without an extra Java object tree |
| `rest-sample-cache-writer.properties` | Local settings |

The application uses the managed four-argument `ProjectionWriterApplication.runCache(...)` overload.
Two method references create the repository and materializer. The library owns Redis, scheduler,
repository, and shutdown order. SQL queries and JSON business shape remain explicit Java code.

## Maven Package Access

GitHub Packages requires a token with `read:packages`. The token also needs access to the private `rust-sample-model` repository.

Add these server IDs to `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
  <server>
    <id>github-rust-sample-model</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

## Common Problems

| Symptom | Check |
|---|---|
| Maven returns `401` | Token, private repository access, and server IDs |
| Writer cannot connect to PostgreSQL | JDBC URL, port, username, and password |
| Reader sees old or missing data | Projection namespace, refresh interval, TTL, and writer logs |
| Two replicas publish the same data | Projection lock names must match across replicas |
| Redis memory grows too quickly | Reduce retained versions, TTLs, index limits, or active projections |

## More Detail

- [Turkish user guide](docs/USER_GUIDE.tr.md)
- [Turkish PDF guide](docs/rest-sample-cache-writer-user-guide.tr.pdf)
- [Production settings](src/main/resources/config/production.properties)
- [Advanced tuning](src/main/resources/config/advanced-tuning.properties)
- [v0.6.0 release notes](docs/RELEASE_NOTES_v0.6.0.md)
