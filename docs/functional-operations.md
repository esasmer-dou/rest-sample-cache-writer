# Bounded Snapshot Work

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Development checkout. No new release is published.

`CustomerJsonWriter` demonstrates `Utf8JsonWriter`: the full JSON is no longer
first assembled as a StringBuilder and String. The JSON field names and UTF-8 content stay the same.
One final byte array is still required by the Redis API. The buffer is not retained between jobs.

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

The agent guide shows `StandaloneTelemetry` and `ProjectionTelemetry.wrap`.
This sample does not silently add an agent dependency.
Keep telemetry disabled unless you need it. Use fixed projection names, never row IDs.
