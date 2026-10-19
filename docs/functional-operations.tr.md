# Sınırlı Snapshot İşlemleri

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Bu özellikler geliştirme kaynaklarındadır. Yeni sürüm henüz yayımlanmadı.

`CustomerJsonWriter`, `Utf8JsonWriter` örneğini kullanır.
Tam JSON önce StringBuilder ve String olarak oluşturulmaz.
Alan isimleri ve UTF-8 içerik aynı kalır.
Redis API için son byte dizisi yine gerekir.
Buffer işler arasında saklanmaz.

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

Agent rehberinde `StandaloneTelemetry` ve `ProjectionTelemetry.wrap` örnekleri vardır.
Sample otomatik agent bağımlılığı eklemez.
İhtiyacınız yoksa telemetry kapalı kalsın.
İsimlerde sabit projection adı kullanın. Satır numarası kullanmayın.
