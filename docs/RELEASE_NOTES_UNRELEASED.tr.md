# Henüz Yayımlanmamış Belge Düzeltmeleri

[English](RELEASE_NOTES_UNRELEASED.md) | [Türkçe](RELEASE_NOTES_UNRELEASED.tr.md)

Sample `0.8.0`, cache `0.9.0`, platform `4.8.0` ve isteğe bağlı agent/cache adaptörü
`0.7.1` için yalnız belge düzeltmesidir; yeni özellik sürümü değildir.

- Snapshot-batch/hazırlanmış JSON için eski yayımlanmamış iddiaları kaldırıldı; sample, kütüphane ve advanced dosya değerleri ayrıldı.
- Fixed-delay zamanlama, veri TTL'i, process zamanlaması ve replica kilidi/fencing açıklandı; anında devralma veya iş iptali garantisi verilmedi.
- Kaynak kodla eşleşen, yenilemeye ait encoder örneği eklendi; senkron hazırlama/yayın ile eksik async/outbox sample akışları ayrıldı.
- HTTP sunucusu olmayan writer için mevcut `-Pglowroot` girişi ve adaptör gösterildi; REST diagnostics endpoint'i olmadığı belirtildi.
- Türkçe bağlantılar ve kullanıcı rehberindeki bulunmayan jlink akışı düzeltildi. PDF'ler önceki kopya olarak kaldı.

Runtime varsayılanları ve geçmiş sürüm/test beyanları değişmez. Yeni performans
doğrulaması iddia edilmez.
