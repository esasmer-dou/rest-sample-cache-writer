# rest-sample-cache-writer 0.6.0

`0.6.0` is the reference PostgreSQL-to-Redis projection writer for the `4.2.0` platform line.

## What Changed

- Uses `rust-java-platform-parent:4.2.0`, `rust-java-starter-cache-writer`,
  `java-rust-cache:0.7.0`, and `rust-sample-model:0.4.0`.
- Repeated compiler and codegen declarations were removed from the application POM.
- Projection scheduling, distributed locks, TTL validation, bounded database reads, and native Redis
  writes remain managed by the library.
- SQL selection and business-specific JSON mapping remain explicit application code.

## Run

```powershell
mvn clean verify
mvn exec:java
```

Projection names, Redis key formats, TTL safety rules, and lock ownership semantics remain unchanged.
