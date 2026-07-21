# rest-sample-cache-writer

[English](README.md) | [Türkçe](README.tr.md)

PostgreSQL verisini okuyup Redis'e hazır JSON snapshot'ları yazan küçük bir scheduler uygulamasıdır.

- SQL, iş dönüşümü ve scheduler kararları Java'da kalır.
- Redis I/O işlemlerini `java-rust-cache` üzerinden Rust yapar.
- Uygulama REST API açmaz.
- Uygulama Dubbo kullanmaz.
- Her projection'ın kendi çalışma aralığı, TTL değeri ve distributed lock'u vardır.

Kullanılan sürümler: `java-rust-cache:0.5.0`, `rust-sample-model:0.3.0`.

## Buradan Başlayın

REST pod'larının her istekte PostgreSQL'e gitmesini istemiyorsanız bu sample'ı kullanın.

Projection, belirli bir endpoint grubu için hazırlanmış cache görünümüdür. Müşteri detayı ve kampanya adayları iki ayrı projection örneğidir.

```text
PostgreSQL -> bu writer -> Redis -> cache reader -> HTTP istemcisi
```

## Hızlı Başlangıç

### 1. PostgreSQL ve Redis'i başlatın

PowerShell'de çalıştırın:

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

### 2. Tek snapshot yayınlayın

Bu repo dizininde çalıştırın:

```powershell
$env:GITHUB_PACKAGES_TOKEN="READ_PACKAGES_YETKILI_TOKEN"

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

Beklenen log:

```text
cache refresh published projection=detail version=<version> keys=<count>
```

### 3. Veriyi okuyun

[`rest-sample-cache-reader`](https://github.com/esasmer-dou/rest-sample-cache-reader) uygulamasını başlatın. Reader aynı Redis'e bağlanır.

## Hangi Veriler Yazılır?

| Projection | Varsayılan namespace | Kullanım amacı |
|---|---|---|
| `detail` | `crm.customer.detail` | Müşteri profili |
| `segment` | `crm.customer.segment` | Segmentteki müşteriler |
| `status` | `crm.customer.status` | Aktif veya pasif müşteri listesi |
| `campaign` | `crm.customer.campaign` | Kampanya adayları |
| `meta` | `crm.customer.meta` | Snapshot durumu ve sürümü |

Yalnızca ihtiyacınız olan projection'ları açın:

```properties
sample.writer.projections=detail,segment,status,campaign,meta
```

Yalnızca kampanya verisi üreten bir uygulama şu ayarı kullanabilir:

```properties
sample.writer.projections=campaign,meta
```

## Çalışma Aralığı, TTL ve Kilit

Her projection bağımsız çalışır.

```properties
sample.writer.campaign.interval-ms=30000
sample.writer.campaign.cache-ttl-ms=120000
sample.writer.campaign.lock-name=crm.customer.campaign.refresh
sample.writer.campaign.lock-ttl-ms=120000
```

Bu ayarlar şu anlama gelir:

1. Kampanya projection'ı 30 saniyede bir çalışır.
2. Yazılan veri 120 saniye sonra silinir.
3. Aynı anda yalnızca bir replica kampanya verisini yeniler.
4. Başka bir replica farklı bir projection'ı aynı anda yenileyebilir.

Cache TTL değeri refresh interval değerinden uzun olmalıdır. Library hatalı değeri güvenli bir değere çeker ve loglar. Yine de production ayarını açıkça doğru vermelisiniz.

## Redis Çalışma Biçimini Seçin

| Ortam | Ayar |
|---|---|
| Lokal Redis | `reactor.cache.redis.topology=standalone` |
| Redis Sentinel | `reactor.cache.redis.topology=sentinel`, Sentinel node'ları ve master adı |
| Redis Cluster | `reactor.cache.redis.topology=cluster` ve cluster node'ları |

Writer bilinçli olarak yalnızca yazma yapar:

```properties
reactor.cache.redis.access-mode=write-only
```

## Konfigürasyon

Uygulama ayarları şu sırayla okur:

1. `src/main/resources/rest-sample-cache-writer.properties`
2. `reactor.config.file` veya `REACTOR_CONFIG_FILE` ile verilen dosyalar
3. JVM `-D...` değerleri ve desteklenen environment variable'lar

| Dosya | Amacı |
|---|---|
| `rest-sample-cache-writer.properties` | Lokal DB, scheduler, projection, lock ve Redis ayarları |
| `config/production.properties` | Production HikariCP limitleri, TTL değerleri ve Redis timeout'ları |
| `config/advanced-tuning.properties` | Daha büyük batch, projection'a özel lock ve Redis pipeline ayarları |

Production ayarlarını şu şekilde ekleyin:

```powershell
java "-Dreactor.config.file=src/main/resources/config/production.properties" ...
```

Önerilen başlangıç değerleri:

| Property | Başlangıç | Ne zaman değiştirilir? |
|---|---:|---|
| `sample.db.maximum-pool-size` | `2` | DB bekleme süresi yüksekse ve PostgreSQL kapasitesi varsa |
| `sample.writer.scheduler-threads` | `2` | Bağımsız projection'lar zamanında bitmiyorsa |
| `sample.writer.page-size` | `500` | Veritabanı gecikmesi ve heap ölçüldükten sonra |
| `reactor.cache.redis.write-connections` | `2` | Ölçülen darboğaz Redis yazma kuyruğuysa |
| `reactor.cache.redis.max-write-inflight` | `4` | Redis kapasitesi ölçümle doğrulandıysa |

Bütün limitleri aynı anda artırmayın. Bu yaklaşım gerçek darboğazı gizler ve process memory (RSS) değerini yükseltir.

## Birden Fazla Replica

Birden fazla replica güvenle çalışabilir.

- Lock anahtarı projection'a aittir.
- İki replica aynı projection'ı birlikte yazamaz.
- İki replica farklı projection'ları birlikte yazabilir.
- Bir replica durursa lock TTL sonunda diğer replica görevi alır.

Her projection için farklı bir `lock-name` kullanın.

## Kod Haritası

| Dosya | Görevi |
|---|---|
| `RestSampleCacheWriterApplication.java` | Projection scheduler'ını başlatır |
| `CustomerCacheMaterializer.java` | Sorguları çalıştırır ve snapshot yayınlar |
| `PostgresCustomerRepository.java` | SQL ve database paging işlemlerini yönetir |
| `CustomerJsonWriter.java` | Ek Java nesne ağacı oluşturmadan JSON yazar |
| `rest-sample-cache-writer.properties` | Lokal ayarları taşır |

## Maven Package Erişimi

GitHub Packages için `read:packages` yetkili token gerekir. Token'ın private `rust-sample-model` reposuna da erişimi olmalıdır.

Şu server kimliklerini `~/.m2/settings.xml` dosyasına ekleyin:

```xml
<servers>
  <server>
    <id>github</id>
    <username>GITHUB_KULLANICI_ADI</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
  <server>
    <id>github-rust-sample-model</id>
    <username>GITHUB_KULLANICI_ADI</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

## Sık Karşılaşılan Sorunlar

| Belirti | Kontrol edin |
|---|---|
| Maven `401` dönüyor | Token, private repo erişimi ve server kimlikleri |
| Writer PostgreSQL'e bağlanamıyor | JDBC URL, port, kullanıcı adı ve parola |
| Reader eski veya eksik veri görüyor | Projection namespace, interval, TTL ve writer logları |
| İki replica aynı veriyi yazıyor | Projection lock adları bütün replica'larda aynı olmalı |
| Redis memory hızlı büyüyor | Saklanan sürüm, TTL, index limiti ve aktif veri grubu sayısı |

## Ayrıntılı Bilgi

- [Türkçe kullanıcı rehberi](docs/USER_GUIDE.tr.md)
- [Türkçe PDF rehberi](docs/rest-sample-cache-writer-user-guide.tr.pdf)
- [Production ayarları](src/main/resources/config/production.properties)
- [Advanced tuning ayarları](src/main/resources/config/advanced-tuning.properties)
- [v0.4.0 release notları](docs/RELEASE_NOTES_v0.4.0.md)
