# Bounded Snapshot Work

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

These examples belong to released sample `0.8.0`, cache `0.9.0` and platform `4.8.0`
(optional agent `0.7.1`). Use the aligned published packages and native files.

## Feature And Run Map

| Feature / coverage | Select and run | Failure / resource boundary |
| --- | --- | --- |
| APT projection registry, namespace/TTL/locks | Default; [Quick Start](../README.md#quick-start), `-Dsample.writer.run-once=true` for one pass | Java owns SQL/JSON; fenced publication, no HTTP server |
| Prepared UTF-8 JSON and bounded paging/batches | Default materializer; example below | Final `byte[]` still allocated; no full-table list |
| Snapshot-batch / Redis pipelining | Base file enables snapshot-batch; limits below | Synchronous publication, not async staging; never blindly replay ambiguous writes |
| Phase-anchored schedules | Opt-in `sample.writer.phase-anchored=true`; [timing recipe](../README.md#frequent-refreshes-without-schedule-drift) | Missed slots skipped; no extra worker; default fixed-delay unchanged |
| Headless Glowroot | `-Pglowroot` plus enabled/collector settings; [run command](../README.md#glowroot-agent-use) | Agent/cache adapter `0.7.1`; no REST diagnostics endpoint; opt-in memory/exporter cost |
| Async staging / outbox | No executable sample here; outbox design below | Library capability/design only, not enabled by a profile or batch switch |

## Batch Settings And Publication

| Key | Base file | Advanced overlay |
| --- | --- | --- |
| `sample.writer.snapshot-batch-size` | `256` entries per writer batch, shared by projection writers | `512` |
| `sample.writer.page-size` | `500` DB rows per detail page | `1000` |
| `sample.writer.scheduler-threads` | `2` | `3` |
| `reactor.cache.redis.write-connections` | `2` | `4` |
| `reactor.cache.redis.max-write-inflight` | Not set in the base file; production overlay sets `4` | `16` |
| `reactor.cache.redis.snapshot-batch-enabled` | `true` in this sample; library default remains `false` | Inherits `true` |

An entry limit is not a byte limit or a customer count: detail writes both ID and
customer-number index entries. DB page size, Redis batch size and in-flight writes
are separate bounds. Larger values cost memory/concurrency; the advanced file is
not a new default. Production projection intervals/TTLs also differ from the base file.

The materializer calls `refreshSnapshot(...)` synchronously. Bounded writes stage
versioned keys; only successful fenced publication changes the active pointer.
Snapshot-batch combines the final tail, pointer publication and lease release on
supported standalone/Sentinel runtimes; Cluster uses its slot-aware path. It does
not add an async queue. Async staging needs explicit bounded ownership, completion,
failure/cancellation and publish-after-all-writes handling; this sample does not implement it.

## Prepared JSON Example

`CustomerJsonWriter` demonstrates `Utf8JsonWriter`: the full JSON is no longer
first assembled as a StringBuilder and String. The JSON field names and UTF-8 content stay the same.
One final byte array is still required by the Redis API. The buffer is not retained between jobs.

The [detail materializer](../src/main/java/com/reactor/sample/cache/writer/cache/CustomerCacheMaterializer.java)
creates its encoder inside each refresh, then uses it sequentially for that refresh:

```java
return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot -> {
    var encodeDetail = jsonWriter.detailEncoder();
    repository.forEachCustomerPage(pageSize, page -> {
        for (SampleCustomer customer : page) {
            byte[] detail = encodeDetail.apply(customer);
            snapshot.putById(customer.id(), detail);
            snapshot.putIndex("customer-no", customer.customerNo(), detail);
        }
    });
});
```

Do not share this refresh-scoped encoder between concurrent jobs. Projection names
come from `CustomerProjection`; `@GenerateProjectionRegistry` generates registration,
not the SQL, encoder or publication business code.

For large tables, keep the existing keyset pagination and snapshot batch limit.
Do not replace them with a full-table List. A single JSON value must remain within
the writer byte cap and the Redis/native request budget.

## Refresh After A Database Change

`PostgresRefreshOutbox` is an optional library primitive, not enabled by this sample.
The command owner writes the change and a projection notification in one DB transaction.
A scheduled worker drains a bounded batch and calls the existing locked refresher.
It acknowledges the notification only after successful publication.
A newer notification is not removed by an older refresh.

Use the existing scheduler; do not add an unbounded polling executor.
DB acknowledgement requires a writable DB connection. This writer's read-only DB role
must not silently be promoted. Grant a separate outbox role deliberately if you use this pattern.

## Observe A Non-Web Job

The `glowroot` Maven profile includes the cache adapter and selects
`TelemetryCacheWriterApplication`, which wraps the managed lifecycle with
`ProjectionTelemetry.writer(...)`. The normal launch has no agent dependency;
setting `reactor.glowroot.enabled=true` alone is insufficient. Use the README run
command, then check the collector for `cache-refresh:<projection>` scheduler work.
A lock skip is normal; failed results/exceptions are errors, not automatic write retries.
Keep telemetry disabled unless needed. Use fixed projection names, never row IDs.
