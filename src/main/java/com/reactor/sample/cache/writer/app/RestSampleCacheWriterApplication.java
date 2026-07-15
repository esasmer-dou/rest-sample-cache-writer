package com.reactor.sample.cache.writer.app;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RustCaches;
import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;

public final class RestSampleCacheWriterApplication {

    private RestSampleCacheWriterApplication() {}

    public static void main(String[] args) {
        CacheProperties properties = CacheProperties.load("rest-sample-cache-writer.properties");

        ProjectionWriterApplication.from(properties, "sample.writer")
                .threadNamePrefix("jdbc-cache-writer")
                .module(context -> {
                    PostgresCustomerRepository repository = context.manage(
                            PostgresCustomerRepository.fromProperties(properties));
                    RustCache cache = context.manage(RustCaches.create(properties.asProperties()));
                    CustomerCacheMaterializer materializer =
                            new CustomerCacheMaterializer(repository, cache, properties);
                    context.refresher(materializer::refreshProjection);
                })
                .run();
    }
}
