# rest-sample-cache-writer 0.6.1

`0.6.1`, PostgreSQL'den Redis'e projection yazan uygulamayı `rust-java-rest:4.3.0`,
`java-rust-cache:0.7.1` ve `rust-sample-model:0.4.1` ile hizalar.

- Projection schedule, distributed lock, TTL doğrulaması, batch ve veri tabanı davranışı değişmedi.
- Writer yalnız gerekli cache-writer starter yüzeyini kullanmaya devam eder.
- Paket yenilenen Windows/Linux native cache binary dosyalarını kullanır.

Build alın ve çalıştırın:

```powershell
mvn clean verify
mvn exec:java
```
