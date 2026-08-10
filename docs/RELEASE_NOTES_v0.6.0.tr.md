# rest-sample-cache-writer 0.6.0

`0.6.0`, `4.2.0` platform çizgisi için PostgreSQL'den Redis'e projection yazan referans uygulamadır.

## Neler Değişti?

- `rust-java-platform-parent:4.2.0`, `rust-java-starter-cache-writer`,
  `java-rust-cache:0.7.0` ve `rust-sample-model:0.4.0` kullanılır.
- Tekrar eden compiler ve codegen tanımları uygulama POM'undan kaldırıldı.
- Projection scheduler, distributed lock, TTL validation, bounded database read ve native Redis write
  lifecycle'ı library tarafından yönetilir.
- SQL seçimi ve business alanına özel JSON mapping uygulama kodunda açık kalır.

## Çalıştırma

```powershell
mvn clean verify
mvn exec:java
```

Projection adları, Redis key formatı, TTL güvenlik kuralları ve lock ownership davranışı değişmedi.
