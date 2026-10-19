# rest-sample-cache-writer 0.7.0

Kullanılan yerlerde platform 4.7.0, cache/Dubbo kütüphaneleri 0.8.0 ve ortak sample sözleşmeleri 0.4.4 ile eşlendi.
Java uygulamasının sorumlulukları değişmez. Mevcut endpoint ve provider sözleşmeleri korunur.

Bu uygulamayla ilgili isteğe bağlı yaş kontrolü, hata, transaction veya komut davranışını [kullanım örneklerinden](functional-operations.tr.md) seçin. Gerekmeyen özelliği açmayın. Bu geçiş için heap, worker, kuyruk veya timeout büyütmeniz gerekmez.

Paketteki eşleşen native dosyayı kullanın. Üzerine eski DLL/SO kopyalamayın. Production veri kaynağı, keşif ve bellek sınırlarını kendi deployment ayarlarınızda belirleyin.

## Doğrulama Notu

Eş koşullu Linux ölçümünde cache c16/c64 RPS ve p99 değerleri iyileşti. Writer tekrar doğrulamasında ortalama tepe RSS 92,417 MiB'den 91,382 MiB'ye düştü. Ancak son boşta RSS 77,268 MiB'den 78,591 MiB'ye çıktı. Bu artış yayın için kabul edilen bir risktir. Birlikte ölçülen consumer tarafında da kabul edilmiş sapmalar vardır. [Ölçüm kapsamını ve değişmeyen katı kontrol sonuçlarını](https://github.com/esasmer-dou/rust-java-rest/blob/v4.7.0/docs/release-notes/v4.7.0.tr.md) inceleyin.

[Hızlı başlangıç](../README.tr.md) | [English](RELEASE_NOTES_v0.7.0.md)
