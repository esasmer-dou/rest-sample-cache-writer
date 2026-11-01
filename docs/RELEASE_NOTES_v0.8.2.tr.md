# rest-sample-cache-writer 0.8.2

[English](RELEASE_NOTES_v0.8.2.md) | [Türkçe](RELEASE_NOTES_v0.8.2.tr.md)

Koordineli güncelleme: 1 Kasım 2026.

## Bağımlılıklar

Cache `0.9.2` ile hizalayan `com.reactor:rust-java-platform-parent:4.8.2` parent'ını
koruyun. Mevcut POM tanımları; PostgreSQL/Hikari ve eklenti ayarları ayrıca korunur:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-cache-writer</artifactId>
  <version>4.8.2</version>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-jdbc</artifactId>
  <version>4.8.2</version>
</dependency>
<dependency>
  <groupId>com.reactor.sample</groupId>
  <artifactId>rust-sample-model</artifactId>
  <version>0.5.2</version>
</dependency>
```

[Maven erişimine](../README.tr.md#maven-package-erişimi) bakın.
`mvn -B -ntp -DskipTests clean package` ile yeniden paketleyin; bu bir test sonucu değildir.

## Mevcut Akış Ve Geçiş

Writer; PostgreSQL'den Redis'e yayın, sınırlı JDBC işi, projection zamanlaması,
kilit, fencing ve TTL korumasının sahibi olmaya devam eder. HTTP API sunmaz.
Hazırlanmış JSON alan adları iş dönüşümünü kaldırmaz, veri kümesini değiştirmez.
Reader'ın `CacheResponses.reply`/isteğe bağlı L1 yolu sonraki aşamadır; writer API'si değildir.
[Compose tarifi](../README.tr.md#compose) ortak topolojiyi çalıştırır; yalnız katalog
sunan native overlay, DB satırlarını customer RPC yerine açık SQL ile değiştirir.

JNI ABI, projection namespace veya ayar anahtarı geçişi gerekmez.
Hizalı bağımlılığın native dosyasını kullanın; eski SO/DLL dosyasını kopyalamayın.
Yayın ve tam kabul, ilgili adayın kesin doğrulama kaydını gerektirir.
Burada yeni throughput, p99, bellek veya soak PASS iddiası yoktur; hosted uyumluluk
ile EmaTech performans/soak kanıtı ayrı değerlendirilir.
