# rest-sample-cache-writer 0.5.0

`0.5.0` reduces writer lifecycle and projection wiring code while keeping SQL and JSON mapping
explicit.

## What Changed

- Uses `java-rust-cache:0.6.0` and `rust-sample-model:0.3.1`.
- The managed writer launcher creates, owns, and closes the PostgreSQL resource.
- Generated projection registry wiring replaces repeated projection selection code.
- The materializer keeps only business SQL-to-JSON mapping and projection refresh behavior.

## Compatibility

Projection names, namespaces, Redis keys, refresh intervals, TTL safety rules, distributed locks,
and PostgreSQL schema behavior are unchanged.

## Run Once

```powershell
mvn clean package
mvn -Dsample.writer.run-once=true exec:java
```

The release JAR contains the application classes. Use Maven as above, or build the documented jlink
image, so PostgreSQL, Redis, and native runtime dependencies are included correctly.
