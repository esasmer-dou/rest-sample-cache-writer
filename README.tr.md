# rest-sample-cache-writer

[English](README.md) | [Turkish](README.tr.md)

Rust-Java ekosistemi için minimum yüzeyli PostgreSQL-to-Redis cache writer örneği.

Bu uygulamanın tek işi vardır. PostgreSQL'den okur ve Redis'e yazar.

REST endpoint açmaz. Dubbo kullanmaz. Java read model üretir. Redis yazma işi `java-rust-cache` ile Rust tarafında yapılır.

Bu örnek `com.reactor:java-rust-cache:0.2.1` ile çalışır. Paket Windows ve Linux native binary'lerini içerir.

## İçindekiler

1. [Kopyala-Yapıştır: Müşteri Cache Snapshot'ı Yaz](#kopyala-yapıştır-müşteri-cache-snapshotı-yaz)
2. [Maven Package Erişimi](#maven-package-erişimi)
3. [Gerçek Senaryo](#gerçek-senaryo)
4. [Ne Üretiyor?](#ne-üretiyor)
5. [TTL Modeli](#ttl-modeli)
6. [Senaryoya Göre TTL Reçeteleri](#senaryoya-göre-ttl-reçeteleri)
7. [Production Redis Topolojisi](#production-redis-topolojisi)
8. [Önemli Property'ler](#önemli-propertyler)
9. [Sözlük](#sözlük)
10. [Production Notları](#production-notları)

## Kopyala-Yapıştır: Müşteri Cache Snapshot'ı Yaz

Bu senaryoda PostgreSQL kaynak veridir. Redis read model deposudur. Writer uygulaması DB'den okur ve
Redis'e hazır JSON snapshot yazar. REST endpoint açmaz.

Bu komutları `rest-sample-cache-writer` dizininde çalıştırın:

```powershell
docker rm -f rs-cache-postgres-test rs-cache-redis-test 2>$null

docker run -d --name rs-cache-postgres-test `
  -e POSTGRES_DB=reactor_sample `
  -e POSTGRES_USER=reactor `
  -e POSTGRES_PASSWORD=reactor `
  -p 15432:5432 postgres:15.7-alpine

docker run -d --name rs-cache-redis-test `
  -p 16379:6379 redis:8.2.1-alpine3.22

$env:GITHUB_PACKAGES_TOKEN="READ_PACKAGES_YETKILI_TOKEN"
mvn -q clean package
mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"

$cp = Get-Content target\cp.txt
java "-Dsample.writer.run-once=true" `
  "-Dsample.writer.initial-delay-ms=0" `
  "-Dsample.db.jdbc-url=jdbc:postgresql://127.0.0.1:15432/reactor_sample" `
  "-Dsample.db.username=reactor" `
  "-Dsample.db.password=reactor" `
  "-Dreactor.cache.redis.host=127.0.0.1" `
  "-Dreactor.cache.redis.port=16379" `
  -cp "target\classes;$cp" `
  com.reactor.sample.cache.writer.app.RestSampleCacheWriterApplication
```

Başarılı çalışmada şu mesaja benzer bir çıktı görürsünüz:

```text
cache refresh published projection=detail version=<version> keys=<count>
cache refresh published projection=campaign version=<version> keys=<count>
```

Bu işlemden sonra `rest-sample-cache-reader` aynı Redis üzerinden müşteri profilini, segment
listelerini ve kampanya adaylarını REST JSON olarak okuyabilir.

## Maven Package Erişimi

Bu örnek `java-rust-cache` bağımlılığını GitHub Packages üzerinden çeker. Maven bu paketi indirebilmek için kimlik bilgisi ister; bu GitHub Packages'in normal erişim modelidir.

Maven `settings.xml` dosyana `read:packages` yetkisi olan bir GitHub token eklemelisin. Aşağıdaki `<id>` değerini aynı bırak; bu değer `pom.xml` içindeki repository id'siyle eşleşmelidir:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

Maven çalıştırmadan önce `GITHUB_PACKAGES_TOKEN` environment variable olarak verilmelidir:

```powershell
$env:GITHUB_PACKAGES_TOKEN="READ_PACKAGES_YETKILI_TOKEN"
mvn -q dependency:resolve
```

Maven `401 Unauthorized` dönerse önce token'ın `read:packages` yetkisini, environment variable'ın shell tarafından görüldüğünü ve server id eşleşmesini kontrol et.

## Container Runtime Notu

Minimal container image kullanıyorsan native extract dizini yazılabilir olmalı:

```bash
-Dreactor.cache.native.extract-dir=/tmp/java-rust-cache/native
```

Paket içindeki Linux native binary manylinux2014/glibc 2.17 tabanında build edilir. CentOS 8, UBI 8/9, Ubuntu/Jammy ve Semeru/OpenJ9 gibi yaygın glibc tabanlı image’larda çalışması hedeflenir. Custom native build kullanırsan native library’yi platformunun desteklediği en eski Linux base üzerinde build et.

## Gerçek Senaryo

PostgreSQL verisini bir servis sahipleniyor ama REST pod’larının her okuma isteğinde DB’ye gitmesini istemiyorsan bu örnek doğru modeldir.

Akış:

1. `rest-sample-cache-writer`, her projection için ayrı scheduled job başlatır.
2. Her projection kendi interval değeriyle çalışır.
3. Her projection kendi Redis lock değerini kullanır.
4. Lock sahibi PostgreSQL'den okur ve o projection'ı publish eder.
5. `rest-sample-cache-reader`, bu snapshot’ı REST JSON olarak servis eder.

Birden fazla replica güvenlidir. İki replica farklı projection'ları aynı anda publish edebilir. Aynı projection yine tek Redis lock ile korunur.

## Ne Üretiyor?

| Projection | Namespace | Varsayılan TTL | Redis abstraction | Gerçek kullanım |
|---|---|---:|---|---|
| Müşteri detayı | `crm.customer.detail` | `1800000` | `getById(id)` ve `getIndex("customer-no", customerNo)` | Müşteri profil ekranı ve çağrı merkezi hızlı arama |
| Segment listesi | `crm.customer.segment` | `300000` | `getIndex("segment", segment)` | Pilot/enterprise müşteri listesi |
| Status listesi | `crm.customer.status` | `300000` | `getIndex("status", status)` | Aktif/pasif müşteri ekranı |
| Kampanya adayları | `crm.customer.campaign` | `120000` | `getIndex("campaign", "retention")` | Pazarlama aday havuzu |
| Snapshot metadata | `crm.customer.meta` | `300000` | `getMeta()` | Operasyonel kontrol/debug |

Payload özellikle nested hazırlandı: `customer`, `contact`, `profile`, `loyalty`, `risk`, `addresses`, `lastOrders`, `audit`.

## TTL Modeli

Writer artık iki seviyede TTL kullanabilir:

```properties
sample.writer.cache-ttl-ms=600000
sample.writer.detail.cache-ttl-ms=1800000
sample.writer.segment.cache-ttl-ms=300000
sample.writer.status.cache-ttl-ms=300000
sample.writer.campaign.cache-ttl-ms=120000
sample.writer.meta.cache-ttl-ms=300000
```

Bütün projection’lar aynı süre yaşayacaksa sadece `sample.writer.cache-ttl-ms` yeterlidir. Veri tiplerinin tazelik ihtiyacı farklıysa projection TTL kullan. Örneğin müşteri detayı daha uzun yaşayabilir, kampanya adayları ise daha kısa TTL ile tutulmalıdır.

Aynı snapshot içindeki rastgele key’lere farklı TTL vermek doğru değildir. Bu, reader tarafında parçalı snapshot görülmesine neden olabilir. Bu örnek bunun yerine ayrı namespace kullanır. Böylece her projection kendi içinde tutarlı kalır.

## Senaryoya Göre TTL Reçeteleri

Bu örnekleri başlangıç noktası olarak kullan. Her veri TTL değeri ilgili projection'ın refresh interval değerinden uzun olmalıdır. TTL, interval değerinden kısa olursa reader yeni publish tamamlanmadan önce süresi dolmuş projection görebilir.

### Senaryo: Müşteri Profili Sabit, Kampanya Akışı Daha Taze

Profil ekranı yavaş değişiyor ama kampanya uygunluğu sık değişiyorsa bu ayar doğru başlangıçtır. REST reader müşteri detayını daha uzun süre servis eder. Kampanya verisi daha hızlı yenilenir ve daha erken expire olur.

```properties
sample.writer.interval-ms=60000
sample.writer.detail.interval-ms=300000
sample.writer.segment.interval-ms=120000
sample.writer.status.interval-ms=120000
sample.writer.campaign.interval-ms=30000
sample.writer.meta.interval-ms=60000
sample.writer.cache-ttl-ms=600000
sample.writer.detail.cache-ttl-ms=1800000
sample.writer.segment.cache-ttl-ms=300000
sample.writer.status.cache-ttl-ms=300000
sample.writer.campaign.cache-ttl-ms=120000
sample.writer.meta.cache-ttl-ms=300000
```

Operasyonel etkisi: Profil endpoint’leri kaçırılan bir writer çalışmasını daha iyi tolere eder. Kampanya endpoint’leri daha taze kalır ve writer durursa daha hızlı cache miss üretir.

### Senaryo: Bütün Veriler İçin Tek Tazelik Penceresi

Bütün projection’lar aynı kaynaktan üretiliyor ve aynı tazelik süresi yeterliyse bu model daha basittir. İlk production geçişi için iyi başlangıçtır.

```powershell
java "-Dsample.writer.namespace=crm.customer.prod" `
  "-Dsample.writer.cache-ttl-ms=900000" `
  "-Dsample.writer.interval-ms=300000" `
  -cp "target\classes;$cp" `
  com.reactor.sample.cache.writer.app.RestSampleCacheWriterApplication
```

Runtime’da verilen base değer dosyadaki projection default’larının önüne geçer. Writer otomatik olarak şu namespace’leri üretir: `crm.customer.prod.detail`, `crm.customer.prod.segment`, `crm.customer.prod.status`, `crm.customer.prod.campaign` ve `crm.customer.prod.meta`.

Aynı kural lock adları için de geçerlidir. `-Dsample.writer.lock-name=crm.customer.prod.refresh` verirsen writer `crm.customer.prod.refresh.detail`, `crm.customer.prod.refresh.segment` ve diğer projection lock adlarını üretir.

### Senaryo: Yoğun Trafikli Kampanya Ekranı

Kampanya endpoint’i yüksek trafik alıyor ve kural değişikliklerini hızlı yansıtmak zorundaysa campaign TTL kısa tutulur. Detail TTL uzun kalır; böylece Redis’e gereksiz detay yazma baskısı oluşturulmaz.

```yaml
env:
  - name: SAMPLE_WRITER_INTERVAL_MS
    value: "30000"
  - name: SAMPLE_WRITER_LOCK_TTL_MS
    value: "120000"
  - name: SAMPLE_WRITER_SCHEDULER_THREADS
    value: "2"
  - name: SAMPLE_WRITER_DETAIL_INTERVAL_MS
    value: "300000"
  - name: SAMPLE_WRITER_SEGMENT_INTERVAL_MS
    value: "120000"
  - name: SAMPLE_WRITER_STATUS_INTERVAL_MS
    value: "120000"
  - name: SAMPLE_WRITER_CAMPAIGN_INTERVAL_MS
    value: "30000"
  - name: SAMPLE_WRITER_META_INTERVAL_MS
    value: "60000"
  - name: SAMPLE_WRITER_DETAIL_CACHE_TTL_MS
    value: "1800000"
  - name: SAMPLE_WRITER_SEGMENT_CACHE_TTL_MS
    value: "180000"
  - name: SAMPLE_WRITER_STATUS_CACHE_TTL_MS
    value: "180000"
  - name: SAMPLE_WRITER_CAMPAIGN_CACHE_TTL_MS
    value: "45000"
  - name: SAMPLE_WRITER_META_CACHE_TTL_MS
    value: "60000"
```

Operasyonel etkisi: Kampanya tazeliği artar, fakat writer daha sık çalışır. DB sorgu süresi, Redis write latency ve `reactor.cache.redis.max-write-inflight` birlikte izlenmelidir.

### Senaryo: Redis Memory Baskısı Var

Redis memory limiti dar ve eski read model’lerin hızlı temizlenmesi gerekiyorsa bu reçete kullanılabilir. Bu model düşük trafikli back-office ekranları için uygundur. Kritik müşteri API’leri için daha uzun TTL tercih edilmelidir.

```properties
sample.writer.interval-ms=120000
sample.writer.detail.interval-ms=300000
sample.writer.segment.interval-ms=120000
sample.writer.status.interval-ms=120000
sample.writer.campaign.interval-ms=60000
sample.writer.meta.interval-ms=120000
sample.writer.detail.cache-ttl-ms=360000
sample.writer.segment.cache-ttl-ms=240000
sample.writer.status.cache-ttl-ms=240000
sample.writer.campaign.cache-ttl-ms=180000
sample.writer.meta.cache-ttl-ms=240000
```

Operasyonel etkisi: Redis eski key’leri daha kısa süre tutar. Bedeli, writer kesintilerine daha düşük toleranstır. Writer TTL süresinden uzun durursa reader endpoint’leri doğru şekilde cache miss response döner.

## Hızlı Çalıştırma

Geçici PostgreSQL ve Redis başlat:

```powershell
docker run -d --name rs-cache-postgres-test `
  -e POSTGRES_DB=reactor_sample `
  -e POSTGRES_USER=reactor `
  -e POSTGRES_PASSWORD=reactor `
  -p 15432:5432 postgres:15.7-alpine

docker run -d --name rs-cache-redis-test -p 16379:6379 redis:8.2.1-alpine3.22
```

Cache’i bir kez doldur:

```powershell
mvn -q clean package
mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
$cp = Get-Content target\cp.txt
java "-Dsample.writer.run-once=true" `
  "-Dsample.writer.initial-delay-ms=0" `
  "-Dreactor.cache.redis.port=16379" `
  -cp "target\classes;$cp" `
  com.reactor.sample.cache.writer.app.RestSampleCacheWriterApplication
```

Beklenen çıktı:

```text
cache refresh published projection=detail version=<version> keys=<count>
cache refresh published projection=campaign version=<version> keys=<count>
```

## Production Redis Topolojisi

Bu writer production’da tek standalone Redis’e bağımlı olmamalıdır. Redis erişilemezse reader son geçerli version’ı TTL dolana kadar servis etmeye devam eder; fakat yeni snapshot publish edilemez.

Redis tek writable primary ile çalışıyor ve failover Sentinel tarafından yönetiliyorsa Sentinel kullan:

```yaml
env:
  - name: REACTOR_CACHE_REDIS_TOPOLOGY
    value: "sentinel"
  - name: REACTOR_CACHE_REDIS_NODES
    value: "redis-sentinel-0:26379,redis-sentinel-1:26379,redis-sentinel-2:26379"
  - name: REACTOR_CACHE_REDIS_SENTINEL_MASTER_NAME
    value: "mymaster"
  - name: REACTOR_CACHE_REDIS_WRITE_CONNECTIONS
    value: "2"
  - name: REACTOR_CACHE_REDIS_MAX_WRITE_INFLIGHT
    value: "4"
```

Snapshot key’leri Redis node’larına dağıtılacaksa Cluster kullan:

```yaml
env:
  - name: REACTOR_CACHE_REDIS_TOPOLOGY
    value: "cluster"
  - name: REACTOR_CACHE_REDIS_NODES
    value: "redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379"
  - name: REACTOR_CACHE_REDIS_CLUSTER_MAX_REDIRECTS
    value: "5"
  - name: REACTOR_CACHE_REDIS_TOPOLOGY_REFRESH_MS
    value: "30000"
```

Cluster’da `reactor.cache.redis.database=0` kalmalıdır. `setMany` cluster-safe çalışır: key’ler hedef node’a göre gruplanır ve redirect durumları yönetilir. Bir projection’ın aynı slot locality’ye ihtiyacı varsa key tasarımında hash tag kullan.

## Önemli Property'ler

| Property | Default | Ne işe yarar? | Ne zaman değiştirirsin? |
|---|---:|---|---|
| `sample.writer.interval-ms` | `60000` | Base refresh interval değeridir. Projection interval bunu override eder. | Ortak default olarak kullan. |
| `sample.writer.lock-name` | `crm.customer.refresh` | Base lock adıdır. Projection lock adları bundan türetilir. | Cache domain değişiyorsa değiştir. Tüm replica'larda aynı olmalı. |
| `sample.writer.lock-ttl-ms` | `300000` | Base lock TTL değeridir. Projection lock TTL bunu override eder. | İlgili projection refresh süresinden uzun olmalı. |
| `sample.writer.scheduler-threads` | `2` | Lokal projection scheduler thread sayısıdır. | Küçük pod için düşük tut. Tek replica içinde paralel projection gerekiyorsa ölçerek artır. |
| `sample.writer.cache-ttl-ms` | `600000` | Base Redis veri yaşam süresidir. | Bütün projection'lar aynı TTL kullanacaksa yeterlidir. |
| `sample.writer.detail.interval-ms` | `300000` | Müşteri detay refresh aralığıdır. | Profil verisi stabilse artır. |
| `sample.writer.detail.lock-name` | `crm.customer.detail.refresh` | Detail refresh Redis lock adıdır. | Sadece tüm writer replica'larla birlikte değiştir. |
| `sample.writer.detail.lock-ttl-ms` | `300000` | Detail lock yaşam süresidir. | Detail refresh süresinden uzun tut. |
| `sample.writer.detail.namespace` | `crm.customer.detail` | Müşteri detayı ve müşteri numarası lookup verisini tutar. | Reader ile aynı değere çekmen gerekir. |
| `sample.writer.detail.cache-ttl-ms` | `1800000` | Müşteri detayının yaşam süresini belirler. | Profil verisi yavaş değişiyorsa artır. Daha taze veri istiyorsan düşür. |
| `sample.writer.segment.interval-ms` | `120000` | Segment listesi refresh aralığıdır. | Segment üyeliği sık değişiyorsa düşür. |
| `sample.writer.segment.lock-name` | `crm.customer.segment.refresh` | Segment refresh Redis lock adıdır. | Sadece tüm writer replica'larla birlikte değiştir. |
| `sample.writer.segment.lock-ttl-ms` | `120000` | Segment lock yaşam süresidir. | Segment refresh süresinden uzun tut. |
| `sample.writer.segment.namespace` | `crm.customer.segment` | Segment listesi verisini tutar. | Reader config ile birlikte değiştir. |
| `sample.writer.segment.cache-ttl-ms` | `300000` | Segment listesinin yaşam süresini belirler. | Segment üyeliği sık değişiyorsa düşür. |
| `sample.writer.status.interval-ms` | `120000` | Status listesi refresh aralığıdır. | Aktif/pasif durumu sık değişiyorsa düşür. |
| `sample.writer.status.lock-name` | `crm.customer.status.refresh` | Status refresh Redis lock adıdır. | Sadece tüm writer replica'larla birlikte değiştir. |
| `sample.writer.status.lock-ttl-ms` | `120000` | Status lock yaşam süresidir. | Status refresh süresinden uzun tut. |
| `sample.writer.status.namespace` | `crm.customer.status` | Status listesi verisini tutar. | Reader config ile birlikte değiştir. |
| `sample.writer.status.cache-ttl-ms` | `300000` | Status listesinin yaşam süresini belirler. | Aktif/pasif durumu sık değişiyorsa düşür. |
| `sample.writer.campaign.interval-ms` | `30000` | Kampanya adayları refresh aralığıdır. | Daha taze kampanya feed'i gerekiyorsa düşür. |
| `sample.writer.campaign.lock-name` | `crm.customer.campaign.refresh` | Campaign refresh Redis lock adıdır. | Sadece tüm writer replica'larla birlikte değiştir. |
| `sample.writer.campaign.lock-ttl-ms` | `120000` | Campaign lock yaşam süresidir. | Campaign refresh süresinden uzun tut. |
| `sample.writer.campaign.namespace` | `crm.customer.campaign` | Kampanya adaylarını tutar. | Reader config ile birlikte değiştir. |
| `sample.writer.campaign.cache-ttl-ms` | `120000` | Kampanya adaylarının yaşam süresini belirler. | Kampanya kuralları sık değişiyorsa kısa tut. |
| `sample.writer.meta.interval-ms` | `60000` | Metadata refresh aralığıdır. | Operasyonel görünürlük ihtiyacına göre ayarla. |
| `sample.writer.meta.lock-name` | `crm.customer.meta.refresh` | Metadata refresh Redis lock adıdır. | Sadece tüm writer replica'larla birlikte değiştir. |
| `sample.writer.meta.lock-ttl-ms` | `120000` | Metadata lock yaşam süresidir. | Metadata refresh süresinden uzun tut. |
| `sample.writer.meta.namespace` | `crm.customer.meta` | Snapshot metadata bilgisini tutar. | Reader config ile birlikte değiştir. |
| `sample.writer.meta.cache-ttl-ms` | `300000` | Metadata yaşam süresini belirler. | En kısa business projection TTL değerine yakın tut. |
| `sample.writer.snapshot-batch-size` | `256` | Redis'e kaç key'in birlikte yazılacağını belirler. | Küçük pod için düşür. Refresh çok yavaşsa ölçerek artır. |
| `sample.writer.page-size` | `500` | DB'den kaç satır okunacağını belirler. | Memory düşükse düşür. DB güçlü ve satırlar küçükse artır. |
| `sample.db.maximum-pool-size` | `2` | DB connection sayısını sınırlar. | Düşük tut. Bu bir scheduled writer'dır. |
| `reactor.cache.redis.write-connections` | `2` | Native Redis write connection açar. | Sadece Redis write latency darboğaz ise artır. |
| `reactor.cache.redis.max-write-inflight` | `4` | Aynı anda kaç Redis write olacağını sınırlar. | Memory'yi korumak için bounded bırak. |
| `reactor.cache.redis.topology` | `standalone` | Redis çalışma modunu seçer. | Production için `sentinel` veya `cluster` kullan. |
| `reactor.cache.redis.nodes` | empty | Sentinel veya Cluster node listesidir. | Topology `sentinel` veya `cluster` ise doldur. |
| `reactor.cache.redis.sentinel.master-name` | empty | Sentinel master adıdır. | Sentinel kullanıyorsan zorunludur. |
| `reactor.cache.redis.sentinel.master-check-ms` | `1000` | Sentinel master değişimini kontrol eder. | Failover toparlanması ölçümde yavaşsa düşür. |

## Sözlük

| Terim | Anlamı |
|---|---|
| TTL | Yaşam süresi. Redis bu süre dolunca key'i siler. |
| Projection | Bir kullanım için hazır okuma modelidir. Örnek: müşteri detayı veya kampanya adayları. |
| Namespace | Redis key ailesinin ön ekidir. Her projection ayrı namespace kullanır. |
| Snapshot | Bir projection'ın tamamlanmış yayınlanmış versiyonudur. |
| Current version | Reader'ın aktif snapshot'ı bulduğu Redis pointer key'idir. |
| Cache miss | Redis'te istenen veri yoktur. Reader kontrollü not-found response döner. |
| Projection lock | Tek projection refresh işini koruyan Redis lock'tur. Farklı projection'lar aynı anda çalışabilir. |
| Backpressure | Queue ve memory sınırsız büyümesin diye konan sert limittir. |
| Sentinel | Redis high availability modudur. Primary failover yönetir. |
| Cluster | Redis sharding modudur. Veri node'lara bölünür. |

## Production Notları

- Production’da schema migration bu process içinde yapılmamalı.
- Sample artık PostgreSQL’i `offset` ile değil keyset pagination ile okur: `id > lastSeenId`. Büyük tablolarda bu pattern’i koru; offset page sayısı büyüdükçe sorgu yavaşlar.
- Bu writer içine REST veya Dubbo ekleme; bu process’in işi DB’den okuyup Redis’e yazmak.
- Read-heavy ekranlar için precomputed JSON kullan.
- `sample.db.maximum-pool-size` değerini düşük tut. Bu bir batch writer, request-serving servis değil.
- Aynı servis birden fazla replica ile koşacaksa projection Redis lock adları tüm replica'larda aynı kalmalı.
