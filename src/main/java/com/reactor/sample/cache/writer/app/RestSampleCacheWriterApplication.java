package com.reactor.sample.cache.writer.app;

import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;

public final class RestSampleCacheWriterApplication {

    private static final String CONFIG = "rest-sample-cache-writer.properties";

    private RestSampleCacheWriterApplication() {}

    public static void main(String[] args) {
        ProjectionWriterApplication.runCache(
                CONFIG,
                "sample.writer",
                PostgresCustomerRepository::fromProperties,
                CustomerCacheMaterializer::new);
    }
}
