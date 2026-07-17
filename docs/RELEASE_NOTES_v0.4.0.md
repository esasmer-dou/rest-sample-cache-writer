# rest-sample-cache-writer 0.4.0

`0.4.0` updates the PostgreSQL-to-Redis writer to the declarative cache library line.

## What's New

- Uses `java-rust-cache:0.5.0` and `rust-sample-model:0.3.0`.
- Uses generated projection registration instead of handwritten projection maps.
- Uses generated JDBC record mappers instead of repeated `ResultSet` conversion code.
- Uses the reusable projection writer application lifecycle from the cache library.
- Keeps SQL, projection shape, JSON shape, lock names, refresh intervals, and TTL decisions explicit.

## Run

```powershell
mvn clean package
java -jar target/rest-sample-cache-writer-0.4.0.jar
```

The writer remains a scheduler process. It does not expose REST and does not add a Dubbo runtime.
