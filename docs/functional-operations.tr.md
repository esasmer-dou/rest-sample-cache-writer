# Sınırlı Snapshot İşlemleri

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Bu örnekler yayımlanmış sample `0.8.0`, cache `0.9.0` ve platform `4.8.0`
sürümlerine aittir (isteğe bağlı agent `0.7.1`). Hizalanmış paketleri ve native dosyaları kullanın.

## Özellik Ve Çalıştırma Tablosu

| Özellik / kapsam | Seçim ve çalıştırma | Hata / kaynak sınırı |
| --- | --- | --- |
| APT projection registry, namespace/TTL/kilit | Varsayılan; [Hızlı Başlangıç](../README.tr.md#hızlı-başlangıç), tek tur için `-Dsample.writer.run-once=true` | SQL/JSON Java'da; fenced yayın; HTTP sunucusu yok |
| Hazırlanmış UTF-8 JSON ve sınırlı sayfalama/batch | Varsayılan materializer; aşağıdaki örnek | Son `byte[]` yine oluşur; tüm tablo tek listeye alınmaz |
| Snapshot-batch / Redis pipeline | Temel dosyada snapshot-batch açık; sınırlar aşağıda | Senkron yayın, async hazırlama değil; sonucu belirsiz yazmayı körlemesine tekrarlamayın |
| Başlangıç fazını koruyan zamanlama | İsteğe bağlı `sample.writer.phase-anchored=true`; [zamanlama reçetesi](../README.tr.md#sık-yenilemelerde-başlangıç-aralığını-koruyun) | Kaçan zaman dilimleri atlanır; ek worker yok; varsayılan fixed-delay değişmez |
| HTTP sunucusu olmadan Glowroot | `-Pglowroot` ve enabled/collector ayarları; [çalıştırma komutu](../README.tr.md#glowroot-agent-kullanımı) | Agent/cache adaptörü `0.7.1`; REST diagnostics endpoint'i yok; bellek/exporter maliyeti isteğe bağlı |
| Async hazırlama / outbox | Burada çalıştırılabilir sample yok; outbox tasarımı aşağıda | Yalnız kütüphane olanağı/tasarım; profil veya batch anahtarıyla açılmaz |

## Batch Ayarları Ve Yayın

| Anahtar | Temel dosya | Advanced ayar dosyası |
| --- | --- | --- |
| `sample.writer.snapshot-batch-size` | Tüm projection writer'larında batch başına `256` kayıt | `512` |
| `sample.writer.page-size` | Detail sayfasında `500` DB satırı | `1000` |
| `sample.writer.scheduler-threads` | `2` | `3` |
| `reactor.cache.redis.write-connections` | `2` | `4` |
| `reactor.cache.redis.max-write-inflight` | Temel dosyada yok; production dosyasında `4` | `16` |
| `reactor.cache.redis.snapshot-batch-enabled` | Bu sample'da `true`; kütüphane varsayılanı `false` kalır | `true` devralınır |

Kayıt sınırı byte sınırı veya müşteri sayısı değildir: detail hem ID hem müşteri
numarası index kaydını yazar. DB sayfası, Redis batch boyutu ve uçuşta yazma sayısı
ayrı sınırlardır. Büyük değerler bellek/eşzamanlılık maliyeti getirir; advanced dosya
yeni varsayılan değildir. Production projection interval/TTL değerleri de temel dosyadan farklıdır.

Materializer, `refreshSnapshot(...)` metodunu senkron çağırır. Sınırlı yazmalar sürümlü
anahtarları hazırlar; aktif sürüm işaretçisi yalnız başarılı fenced yayınla değişir.
Snapshot-batch, uygun standalone/Sentinel runtime'ında son parti, işaretçi yayını ve
kilit bırakmayı birleştirir; Cluster slot uyumlu yolu kullanır. Async kuyruk eklenmez.
Async hazırlama için sınırlı sahiplik, tamamlanma, hata/iptal ve tüm yazmalar bitince
yayınlama akışı açıkça kurulmalıdır; bu sample bunu uygulamaz.

## Hazırlanmış JSON Örneği

`CustomerJsonWriter`, `Utf8JsonWriter` örneğini kullanır.
Tam JSON önce StringBuilder ve String olarak oluşturulmaz.
Alan isimleri ve UTF-8 içerik aynı kalır.
Redis API için son byte dizisi yine gerekir.
Buffer işler arasında saklanmaz.

[Detail materializer](../src/main/java/com/reactor/sample/cache/writer/cache/CustomerCacheMaterializer.java),
encoder'ı her yenilemenin içinde oluşturur ve o yenilemede sırayla kullanır:

```java
return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot -> {
    var encodeDetail = jsonWriter.detailEncoder();
    repository.forEachCustomerPage(pageSize, page -> {
        for (SampleCustomer customer : page) {
            byte[] detail = encodeDetail.apply(customer);
            snapshot.putById(customer.id(), detail);
            snapshot.putIndex("customer-no", customer.customerNo(), detail);
        }
    });
});
```

Yenilemeye ait bu encoder'ı eşzamanlı işler arasında paylaşmayın. Projection adları
`CustomerProjection` içindedir. `@GenerateProjectionRegistry` bağlantıyı üretir;
SQL, encoder veya yayın iş kodunu üretmez.

Büyük tablolarda mevcut anahtar bazlı sayfalamayı ve snapshot batch sınırını koruyun.
Tüm tabloyu tek List içine almayın.
Tek JSON değeri hem writer byte sınırına hem native Redis bütçesine sığmalıdır.

## DB Değişince Cache Yenilensin

`PostgresRefreshOutbox` isteğe bağlıdır. Sample'da kendiliğinden açılmaz.
Yazma servisi, DB değişikliğiyle projection bildirimini aynı transaction'a yazar.
Scheduler sınırlı sayıda bildirimi alır. Mevcut kilitli refresher'ı çağırır.
Yalnız başarılı yayından sonra bildirimi siler.
Eski yenileme, daha yeni bildirimi silemez.

Mevcut scheduler'ı kullanın. Sınırsız polling executor eklemeyin.
Bildirim silmek DB yazma yetkisi gerektirir.
Bu writer'ın salt okunur DB rolünü sessizce değiştirmeyin.
Outbox kullanacaksanız ayrı ve sınırlı yetki tanımlayın.

## Web Olmayan İşi İzleyin

`glowroot` Maven profili cache adaptörünü ekler ve `TelemetryCacheWriterApplication`
girişini seçer. Managed lifecycle, `ProjectionTelemetry.writer(...)` ile sarılır.
Normal başlangıçta agent bağımlılığı yoktur; yalnız `reactor.glowroot.enabled=true`
yazmak yeterli değildir. README'deki komutla çalıştırın; collector'da
`cache-refresh:<projection>` scheduler işlemlerini kontrol edin. Kilit alınamadığı
için atlanan tur normaldir. Başarısız sonuç/exception hata sayılır; yazma otomatik tekrarlanmaz.
İhtiyacınız yoksa telemetri kapalı kalsın. Sabit projection adı kullanın; satır ID'si kullanmayın.
