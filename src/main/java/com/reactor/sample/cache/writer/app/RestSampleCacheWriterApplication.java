package com.reactor.sample.cache.writer.app;

import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;

public final class RestSampleCacheWriterApplication {

    private static final String CONFIG = "rest-sample-cache-writer.properties";

    private RestSampleCacheWriterApplication() {}

    public static void main(String[] args) {
        ProjectionWriterApplication.run(CONFIG, "sample.writer", CacheWriterModule.INSTANCE);
    }
}
