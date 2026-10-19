# rest-sample-cache-writer

[English](README.md) | [Türkçe](README.tr.md)

Yeni kullanım rehberi: [Yeni kullanım örnekleri](docs/functional-operations.tr.md).

PostgreSQL verisini okuyup Redis'e hazır JSON snapshot'ları yazan küçük bir scheduler uygulamasıdır.

- SQL, iş dönüşümü ve scheduler kararları Java'da kalır.
- Redis I/O işlemlerini `java-rust-cache` üzerinden Rust yapar.
- Uygulama REST API açmaz.
- Uygulama Dubbo kullanmaz.
- Her projection'ın kendi çalışma aralığı, TTL değeri ve distributed lock'u vardır.

Kullanılan sürümler: `rust-java-platform-parent:4.7.1`, `java-rust-cache:0.8.0`, `rust-sample-model:0.4.4`.

## Önce Bu Bölümü Okuyun

PostgreSQL verisini Redis'e hazırlama sorumluluğu tek bir scheduler process'inde olacaksa bu sample
doğru başlangıçtır. Ayrı ve ölçülmüş bir sorumluluk yoksa bu process'e REST server veya Redis read
plane eklemeyin.

| Hedef | Bölüm |
| --- | --- |
| PostgreSQL, Redis ve tek publish çalıştırmak | [Hızlı Başlangıç](#hızlı-başlangıç) |
| TTL, refresh ve lock güvenliğini anlamak | [Çalışma Aralığı, TTL ve Kilit](#çalışma-aralığı-ttl-ve-kilit) |
| Standalone, Sentinel veya Cluster seçmek | [Redis Çalışma Biçimini Seçin](#redis-çalışma-biçimini-seçin) |
| Birden fazla replica çalıştırmak | [Birden Fazla Replica](#birden-fazla-replica) |
| Agent desteğinin sınırını anlamak | [Glowroot Agent Kullanımı](#glowroot-agent-kullanımı) |

POM, `rust-java-platform-parent` ve tek bir `rust-java-starter-cache-writer` bağımlılığı kullanır.
Bu starter scheduler process'ine REST runtime eklemez. Kod üreteçleri yalnız derleyici yolunda kalır
ve production sınıfı olarak pakete girmez.

## Güncel Kaynak Ayarları

Bu güncelleme, platformu REST `4.7.1` ve isteğe bağlı agent `0.7.0` ile eşler.
Seçtiğiniz bağımlılığın native dosyasını kullanın. Eski SO/DLL dosyalarını karıştırmayın.
İş kodu, URL'ler ve ayar anahtarları değişmez. Telemetri varsayılan olarak kapalıdır.
HTTP micro bellek testi, veritabanı işinde veya daha ayrıntılı profilde aynı maliyeti garanti etmez.

`0.7.1` sürümü platform parent `4.7.1` kullanır. Uygulamayı hizalanmış bağımlılıklarla
yeniden derleyin. [Sürüm notlarını](docs/RELEASE_NOTES_v0.7.1.tr.md) okuyun. Runtime ayarları ve adresler değişmez.

- Managed writer launcher PostgreSQL kaynağını tek lifecycle içinde oluşturur ve kapatır.
- Generated projection registry bağlantısı tekrar eden projection lookup kodunu kaldırır.
- SQL sorguları ve JSON business mapping açık Java kodu olarak kalır.
- Projection adları, yenileme aralıkları, TTL kuralları, lock'lar ve Redis key'leri değişmedi.
- Cache writer, Redis ABI `6` ve pakete ait SHA-256 bilgisini doğrular. REST runtime başlatmaz.

## Deklaratif Akış

| Sizin yazdığınız kod | Framework'ün ürettiği veya yönettiği alan | Bu process'te hiç açılmayan alan |
| --- | --- | --- |
| SQL ve batch repository | Repository lifecycle ve shutdown sırası | REST server |
| Projection materializer | Projection registry ve schedule'lar | Redis read pool |
| JSON business şekli | Bounded publish pipeline | Dubbo runtime |
| Projection bazlı interval, TTL ve lock | Scheduler ve fenced lock sahipliği | Tekrar eden schedule bağlantı kodu |

Framework lifecycle ve registry boilerplate kodunu kaldırır. SQL, veri seçimi ve JSON business şekli
açık Java kodu olarak kalır. Böylece cache sözleşmesi kolayca incelenebilir.

## Buradan Başlayın

REST pod'larının her istekte PostgreSQL'e gitmesini istemiyorsanız bu sample'ı kullanın.

Projection, belirli bir endpoint grubu için hazırlanmış cache görünümüdür. Müşteri detayı ve kampanya adayları iki ayrı projection örneğidir.

```text
PostgreSQL -> bu writer -> Redis -> cache reader -> HTTP istemcisi
```

```mermaid
flowchart LR
    DB["PostgreSQL"] --> R["Java repository"]
    R --> M["Projection materializer"]
    M -->|"fenced native publish"| C["Redis"]
    C --> API["Cache reader REST API"]
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

## Küçük Linux/OpenJ9 Podları

Linux/OpenJ9 ortamında `-XX:-TransparentHugePage` seçeneğini deneyebilirsiniz.
Consumer'da yarar sağlayan bir ayarı writer'a ölçmeden taşımayın.
Writer'ın derleme ve yenileme yükü farklıdır. Bu seçenek tepe RSS değerini de
artırabilir. Yalnız tekrarlı yenileme testlerinde yarar görürseniz kullanın.
Bu seçenek sample image'ında varsayılan olarak açık değildir. Etkinleştirdiğinizde
OpenJ9, ayırdığı bellek için Linux'tan otomatik büyük sayfa istemez.
Heap limitini düşürmeden ve JIT'i kapatmadan
fiziksel bellek kullanımını azaltabilir. Bu seçenek bir heap limiti değildir.
Sunucunun genel ayarını değiştirmez. Native alanlar yine büyük sayfa kullanabilir.

`JAVA_TOOL_OPTIONS` verirseniz image'daki değerin tamamını değiştirirsiniz.
Mevcut küçük pod image'ı için seçenekleri birlikte koruyun:

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: >-
      -Xms24m -Xmx96m -Xss192k -Xgc:threads=1
      -XX:ActiveProcessorCount=1 -Xtune:virtualized -XX:-TransparentHugePage
```

Bu reçete OpenJ9 içindir. Scheduler thread'leri, yenileme aralığı, Redis batch
boyutu, TTL ve Hikari limitleri değişmez. Aynı sayıda başarılı yenileme için süreyi
ve RSS'i karşılaştırın. Yayınlama yavaşlıyorsa veya yenileme aralığını kaçırıyorsa
tek başına düşük RSS yeterli değildir.

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

## Glowroot Agent Kullanımı

Varsayılan derleme agent bağımlılığı eklemez. İş mantığı ve mevcut ayarlar değişmez.
Telemetri gerekiyorsa `glowroot` Maven profilini seçin.

```powershell
$env:REACTOR_GLOWROOT_ENABLED="true"
$env:REACTOR_GLOWROOT_PROFILE="micro"
$env:REACTOR_GLOWROOT_COLLECTOR_ADDRESS="http://127.0.0.1:8181"
$env:REACTOR_GLOWROOT_AGENT_ID="cache-writer-local"
mvn -Pglowroot clean compile exec:java
```

Profil, `java-rust-glowroot-cache-adapter:0.7.0` paketini ekler.
`TelemetryCacheWriterApplication` mevcut writer yaşam döngüsünü kullanır. Projection adları ayarlardan alınır. Collector UI'da `Scheduler` türünü ve `cache-refresh:<projection>` adını seçin.

Spring veya REST sunucusu eklenmez. Agent açıksa izole Rust exporter çalışır.
Yeni Java executor oluşturulmaz. Payload, SQL değeri veya müşteri bilgisi gönderilmez.
Normal kilit atlaması hata değildir. Başarısız sonuç ve exception hata sayılır. Yazma işlemi yeniden denenmez.

`REACTOR_GLOWROOT_ENABLED=false` ile agent'ı kapatın.
Bağımlılığı da çıkarmak için `glowroot` profilini seçmeden derleyin.
Bootstrap JAR'ını tek başına eklemek bu yaşam döngüsünü başlatmaz.

Maven `settings.xml` dosyasına `github-glowroot` kimlikli server ekleyin.
Geçerli `read:packages` token'ı kullanın. SSH Maven paketlerini indirmez.
`micro` ile başlayın. HTTP testindeki 3 MiB boşta medyan sınırı, bu uygulamanın
her iş yükünde aynı maliyetle çalışacağının garantisi değildir.

[Kurulum ve profil ayrıntıları](https://github.com/esasmer-dou/java-rust-glowroot-agent/blob/v0.7.0/docs/functional-operations.tr.md)

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
| `CustomerCacheMaterializer.java` | Projection refresher'ı uygular; generated registry her projection'ı bağlar |
| `PostgresCustomerRepository.java` | SQL ve database paging işlemlerini yönetir |
| `CustomerJsonWriter.java` | Ek Java nesne ağacı oluşturmadan JSON yazar |
| `rest-sample-cache-writer.properties` | Lokal ayarları taşır |

Uygulama, dört parametreli managed `ProjectionWriterApplication.runCache(...)` metodunu kullanır.
İki method reference repository ve materializer oluşturur. Redis, scheduler, repository ve kapanış
sırası library tarafından yönetilir. SQL sorguları ve JSON business şekli açık Java kodu olarak kalır.

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
| `reactor.glowroot.enabled=true` yazdım ama veri yok | Bu sade Java sample agent çalışma katmanını başlatmaz; yukarıdaki destek sınırını okuyun |

## Production Kontrol Listesi

- `reactor.cache.redis.access-mode=write-only` değerini koruyun.
- Her TTL değerini ilgili projection yenileme aralığından uzun tutun.
- Bağımsız çalışan her projection için sabit ve farklı lock adı kullanın.
- SQL paging ve batch size değerlerini sınırlayın. Kaynak tablonun tamamını tek listeye almayın.
- Hikari connection sayısını scheduler thread sayısına göre değil, veritabanı kapasitesine göre belirleyin.
- Publish işlemini idempotent yapın. Aktif snapshot sürümünü değiştirmeden önce fenced ownership kullanın.
- İki replica, lock devri, Redis restart/failover, DB timeout, yarım kalan publish ve recovery testi yapın.
- Publish süresi, schedule gecikmesi, DB pool wait, Redis p99, RSS ve saklanan sürümleri ölçün.
- Yalnız telemetri için Spring Boot veya REST runtime eklemeyin.

## Kısa Sözlük

| Terim | Basit anlamı |
| --- | --- |
| Projection | Bir endpoint veya sorgu ailesi için hazırlanmış cache görünümü |
| Refresh interval | Projection'ın ne sıklıkla yeniden üretildiği |
| TTL | Yeni başarılı refresh olmadan verinin Redis'te kalacağı süre |
| Fenced lock | Eski replica'nın publish yapmasını engelleyen ownership kontrollü kilit |
| Active version | Reader'ın o anda çözdüğü snapshot sürümü |
| Materializer | Kaynak satırları okuyup cache gösterimini üreten Java kodu |
| Bootstrap agent JAR | Yalnız `-javaagent` argümanlarını property'lere çeviren, runtime içermeyen küçük JAR |

## Ayrıntılı Bilgi

- [Türkçe kullanıcı rehberi](docs/USER_GUIDE.tr.md)
- [Türkçe PDF rehberi](docs/rest-sample-cache-writer-user-guide.tr.pdf)
- [Production ayarları](src/main/resources/config/production.properties)
- [Advanced tuning ayarları](src/main/resources/config/advanced-tuning.properties)
- [v0.7.1 sürüm notları](docs/RELEASE_NOTES_v0.7.1.tr.md)
