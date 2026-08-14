# rest-sample-cache-writer 0.6.4

Bu patch, scheduler writer uygulamasını `rust-java-rest:4.5.0` ve `java-rust-cache:0.7.4` ile
hizalar. Paketlenen cache runtime artık temiz native ABI `29/7/6/3` provenance hattını kullanır.

Schedule, TTL, lock, SQL, Redis key ve Java iş dönüşümü davranışı değişmedi.
