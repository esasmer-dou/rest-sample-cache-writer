# rest-sample-cache-writer

[English](README.md) | [Turkish](README.tr.md)

Rust-Java ekosistemi için minimum yüzeyli PostgreSQL-to-Redis cache writer örneği.

Bu süreç REST endpoint açmaz ve Dubbo kullanmaz. PostgreSQL verisini ActiveJDBC + HikariCP ile okur.
Gerçek hayata yakın nested JSON read model üretir ve Redis'e `java-rust-cache` üzerinden yazar.
Redis I/O tarafı Rust tarafından JNI ile yapılır.

Bu örnek `com.reactor:java-rust-cache:0.2.1` ile çalışır. `java-rust-cache` paketi uyumlu
Windows/Linux native Redis bridge binary'lerini içerir. Bu yüzden writer `rust-java-rest` olmadan ve
manuel `java.library.path` vermeden çalışabilir.

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
cache refresh published version=<version> keys=<count>
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

1. `rest-sample-cache-writer`, `sample.writer.interval-ms` aralığıyla uyanır.
2. Redis üzerinde `sample.writer.lock-name` ile lock alır.
3. PostgreSQL’den müşteri verisini sayfa sayfa okur.
4. Redis’e yeni bir versioned snapshot publish eder.
5. `rest-sample-cache-reader`, bu snapshot’ı REST JSON olarak servis eder.

Birden fazla replica güvenlidir. Sadece lock sahibi olan replica publish eder; lock/token/version detayını kullanıcı kodu yönetmez.

## Ne Üretiyor?

| Projection | Redis abstraction | Gerçek kullanım |
|---|---|---|
| Müşteri detayı | `getById(id)` | Müşteri profil ekranı |
| Müşteri numarası lookup | `getIndex("customer-no", customerNo)` | Çağrı merkezi hızlı arama |
| Segment listesi | `getIndex("segment", segment)` | Pilot/enterprise müşteri listesi |
| Status listesi | `getIndex("status", status)` | Aktif/pasif müşteri ekranı |
| Kampanya adayları | `getIndex("campaign", "retention")` | Pazarlama aday havuzu |
| Snapshot metadata | `getMeta()` | Operasyonel kontrol/debug |

Payload özellikle nested hazırlandı: `customer`, `contact`, `profile`, `loyalty`, `risk`, `addresses`, `lastOrders`, `audit`.

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
cache refresh published version=<version> keys=<count>
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

## Önemli Property’ler

| Property | Default | Ne zaman değiştirirsin? |
|---|---:|---|
| `sample.writer.interval-ms` | `60000` | Verinin ne sıklıkla Redis’e basılacağını belirler. Az değişen veri için artır. |
| `sample.writer.lock-ttl-ms` | `300000` | Multiple replica’da aynı işi tek pod’un yapmasını sağlar. Normal refresh süresinden uzun olmalı. |
| `sample.writer.cache-ttl-ms` | `600000` | Redis verisinin yaşam süresi. Refresh interval’dan uzun tut. |
| `sample.writer.snapshot-batch-size` | `256` | Versioned snapshot key’leri için Redis write batch boyutu. Memory-first pod’da düşür; Redis write latency düşük ama refresh yavaşsa ölçerek artır. |
| `sample.writer.page-size` | `500` | DB’den kaç kayıtlık batch okunacağını belirler. Satırlar küçükse ve DB güçlü ise dikkatli artır. |
| `sample.db.maximum-pool-size` | `2` | Bu process request serve etmiyor, scheduled writer. Düşük tutmak doğru. |
| `reactor.cache.redis.write-connections` | `2` | Rust native Redis write connection sayısı. Refresh yavaşsa ölçerek artır. |
| `reactor.cache.redis.max-write-inflight` | `4` | Redis write backpressure limiti. Memory’yi korumak için bounded kalmalı. |
| `reactor.cache.redis.topology` | `standalone` | Production HA/sharding için `sentinel` veya `cluster` seç. |
| `reactor.cache.redis.nodes` | empty | Sentinel node listesi veya Cluster startup node listesi. |
| `reactor.cache.redis.sentinel.master-name` | empty | Sadece Sentinel için zorunludur. |
| `reactor.cache.redis.sentinel.master-check-ms` | `1000` | Sentinel modunda failover sonrası master değişimini ne sıklıkla kontrol edeceğini belirler. Recovery süresi ölçümle yetmiyorsa düşür. |

## Production Notları

- Production’da schema migration bu process içinde yapılmamalı.
- Sample artık PostgreSQL’i `offset` ile değil keyset pagination ile okur: `id > lastSeenId`. Büyük tablolarda bu pattern’i koru; offset page sayısı büyüdükçe sorgu yavaşlar.
- Bu writer içine REST veya Dubbo ekleme; bu process’in işi DB’den okuyup Redis’e yazmak.
- Read-heavy ekranlar için precomputed JSON kullan.
- `sample.db.maximum-pool-size` değerini düşük tut. Bu bir batch writer, request-serving servis değil.
- Aynı servis birden fazla replica ile koşacaksa Redis lock açık kalmalı.
