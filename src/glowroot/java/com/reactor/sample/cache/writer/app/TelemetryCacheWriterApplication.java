package com.reactor.sample.cache.writer.app;

import com.reactor.glowroot.agent.cache.ProjectionTelemetry;
import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;

public final class TelemetryCacheWriterApplication {
    private TelemetryCacheWriterApplication() {}

    public static void main(String[] args) {
        ProjectionWriterApplication.runCache("rest-sample-cache-writer.properties", "sample.writer",
                ProjectionTelemetry.writer(PostgresCustomerRepository::fromProperties, CustomerCacheMaterializer::new));
    }
}
