package com.reactor.sample.cache.writer.app;

import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RustCaches;
import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;

public final class CacheWriterModule implements ProjectionWriterApplication.Module {

    public static final CacheWriterModule INSTANCE = new CacheWriterModule();

    private CacheWriterModule() {}

    @Override
    public void configure(ProjectionWriterApplication.ModuleContext context) {
        CacheProperties properties = context.properties();
        PostgresCustomerRepository repository = context.manage(
                PostgresCustomerRepository.fromProperties(properties));
        RustCache cache = context.manage(RustCaches.create(properties.asProperties()));
        CustomerCacheMaterializer materializer = new CustomerCacheMaterializer(repository, cache, properties);
        context.refresher(materializer::refreshProjection);
    }
}
