# rest-sample-cache-writer 0.6.6

[English](RELEASE_NOTES_v0.6.6.md) | [Turkish](RELEASE_NOTES_v0.6.6.tr.md)

Kullanılan bağımlılıklar platform 4.6.3, cache 0.7.7, Dubbo 0.7.4 ve ortak model/sözleşme 0.4.3 sürümleriyle hizalanır. Endpoint, property, profil ve iş davranışı değişmez. Yalnız seçilen starter bağımlılıkları yüklenir. Yeni runtime bileşeni açılmaz.

## Güncelleme

Maven bağımlılığını veya parent sürümünü güncelleyin. Uygulamayı yeniden derleyin. Annotation processor sürümü platformla hizalı olsun. İşletim sistemi ve işlemcinize uygun paket içi native dosyayı kullanın. Eski sürümün dosyalarını yerinde değiştirmeyin.

İş mantığı Java'da kalır. Runtime reflection veya yeni bir Java I/O katmanı eklenmez. Telemetri kendiliğinden açılmaz. ARM64 native dosyaları CI üzerinde derlenir. Bu sürümün uygulama kontrolleri x64 üzerinde yapılır. Bu patch için yeni ve evrensel bir RSS veya throughput kazanımı iddia edilmez.

GitHub Packages için Maven `settings.xml` içinde geçerli `read:packages` token gerekir. Repo public olsa da bu kural geçerlidir. Git SSH erişimi Maven kimlik doğrulamasından ayrıdır.

