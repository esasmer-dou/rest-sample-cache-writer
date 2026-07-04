package com.reactor.sample.cache.writer.app;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RustCaches;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import com.reactor.rust.cache.scheduler.ProjectionRefreshScheduler;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.config.WriterProperties;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;

import java.util.List;

public final class RestSampleCacheWriterApplication {

    private RestSampleCacheWriterApplication() {}

    public static void main(String[] args) {
        WriterProperties properties = WriterProperties.load();
        List<CacheWriterProjectionSettings> projectionSettings =
                CacheWriterProjectionSettings.resolveAll(properties, "sample.writer");

        PostgresCustomerRepository repository = PostgresCustomerRepository.fromProperties(properties);
        RustCache cache = RustCaches.create(properties.asProperties());
        CustomerCacheMaterializer materializer = new CustomerCacheMaterializer(repository, cache, properties, projectionSettings);
        ProjectionRefreshScheduler scheduler = ProjectionRefreshScheduler.builder()
                .settings(projectionSettings)
                .refresher(materializer::refreshProjection)
                .schedulerThreads(properties.getInt("sample.writer.scheduler-threads"))
                .runOnce(properties.getBoolean("sample.writer.run-once"))
                .threadNamePrefix("activejdbc-cache-writer")
                .build();

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

    private static void shutdown(ProjectionRefreshScheduler scheduler, PostgresCustomerRepository repository, RustCache cache) {
        scheduler.close();
        repository.close();
        cache.close();
    }
}
