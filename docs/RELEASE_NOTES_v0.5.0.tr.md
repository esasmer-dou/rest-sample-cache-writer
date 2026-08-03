# rest-sample-cache-writer 0.5.0

`0.5.0`, SQL ve JSON mapping kodunu açık tutarken writer lifecycle ve projection bağlantı kodunu
azaltır.

## Neler Değişti?

- `java-rust-cache:0.6.0` ve `rust-sample-model:0.3.1` kullanılır.
- Managed writer launcher PostgreSQL kaynağını oluşturur, yönetir ve kapatır.
- Generated projection registry bağlantısı tekrar eden projection seçim kodunu kaldırır.
- Materializer içinde yalnız business SQL-to-JSON mapping ve projection refresh davranışı kalır.

## Uyumluluk

Projection adları, namespace'ler, Redis key'leri, yenileme aralıkları, TTL güvenlik kuralları,
distributed lock'lar ve PostgreSQL schema davranışı değişmedi.

## Bir Kez Çalıştırma

```powershell
mvn clean package
mvn -Dsample.writer.run-once=true exec:java
```

Release JAR'ı uygulama sınıflarını içerir. PostgreSQL, Redis ve native runtime bağımlılıklarının doğru
yüklenmesi için yukarıdaki Maven komutunu veya dokümandaki jlink image'ını kullanın.
