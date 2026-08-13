# rest-sample-cache-writer 0.6.2

`0.6.2`, PostgreSQL'den Redis'e yazan uygulamayı `rust-java-rest:4.4.0`,
`java-rust-cache:0.7.2` ve `rust-sample-model:0.4.1` ile hizalar.

- Scheduler, projection lock, TTL kuralı, database batch ve Redis yazma davranışı değişmez.
- Cache paketi REST ABI `28`, Redis ABI `6` ve Glowroot ABI `1` taşıyan temiz native runtime'ı
  kullanır.
- Bu plain scheduler sample telemetry'yi kendiliğinden başlatmaz. Native Redis sürelerinin
  gönderilmesi için host runtime mikro ajanı açıkça başlatmalıdır.

