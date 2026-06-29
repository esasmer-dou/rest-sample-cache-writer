package com.reactor.sample.cache.writer.app;

import com.reactor.rust.cache.RustCache;
import com.reactor.rust.cache.RustCaches;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.config.WriterProperties;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;
import com.reactor.sample.cache.writer.scheduler.CacheRefreshScheduler;

public final class RestSampleCacheWriterApplication {

    private RestSampleCacheWriterApplication() {}

    public static void main(String[] args) {
        WriterProperties properties = WriterProperties.load();

        PostgresCustomerRepository repository = PostgresCustomerRepository.fromProperties(properties);
        RustCache cache = RustCaches.create(properties.asProperties());
        CustomerCacheMaterializer materializer = new CustomerCacheMaterializer(repository, cache, properties);
        CacheRefreshScheduler scheduler = new CacheRefreshScheduler(materializer, properties);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(scheduler, repository, cache), "cache-writer-shutdown"));
        scheduler.start();

        if (properties.getBoolean("sample.writer.run-once")) {
            scheduler.awaitFirstRun();
            shutdown(scheduler, repository, cache);
            return;
        }

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdown(CacheRefreshScheduler scheduler, PostgresCustomerRepository repository, RustCache cache) {
        scheduler.close();
        repository.close();
        cache.close();
    }
}
