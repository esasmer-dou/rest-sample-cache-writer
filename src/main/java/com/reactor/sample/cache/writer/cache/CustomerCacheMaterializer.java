package com.reactor.sample.cache.writer.cache;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import com.reactor.rust.cache.projection.GenerateProjectionRegistry;
import com.reactor.rust.cache.projection.VersionedJsonProjectionMaterializer;
import com.reactor.rust.cache.projection.VersionedJsonProjectionMaterializer.ProjectionTarget;
import com.reactor.rust.cache.scheduler.ProjectionRefreshResult;
import com.reactor.rust.cache.scheduler.ProjectionRefreshScheduler;
import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter.SnapshotResult;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;
import com.reactor.sample.cache.writer.json.CustomerJsonWriter;
import com.reactor.sample.model.customer.CustomerCounts;
import com.reactor.sample.model.customer.SampleCustomer;
import com.reactor.sample.model.cache.CustomerProjection;

import java.time.Instant;
import java.util.List;

@GenerateProjectionRegistry(CustomerProjection.class)
public final class CustomerCacheMaterializer {

    private final PostgresCustomerRepository repository;
    private final CustomerJsonWriter jsonWriter;
    private final VersionedJsonProjectionMaterializer projections;
    private final int pageSize;
    private final int segmentIndexLimit;
    private final int statusIndexLimit;
    private final int campaignCandidateLimit;

    public static ProjectionRefreshScheduler.ProjectionRefresher create(
            ProjectionWriterApplication.ModuleContext context,
            RustCache cache,
            CacheProperties properties) {
        PostgresCustomerRepository repository = context.manage(
                PostgresCustomerRepository.fromProperties(properties));
        CustomerCacheMaterializer materializer = new CustomerCacheMaterializer(repository, cache, properties);
        return materializer::refreshProjection;
    }

    public CustomerCacheMaterializer(
            PostgresCustomerRepository repository,
            RustCache cache,
            CacheProperties properties) {
        this(repository, cache, properties, CacheWriterProjectionSettings.resolveAll(properties, "sample.writer"));
    }

    public CustomerCacheMaterializer(
            PostgresCustomerRepository repository,
            RustCache cache,
            CacheProperties properties,
            List<CacheWriterProjectionSettings> projectionSettings) {
        this.repository = repository;
        this.jsonWriter = new CustomerJsonWriter();
        int batchSize = properties.getInt("sample.writer.snapshot-batch-size");
        this.pageSize = properties.getInt("sample.writer.page-size");
        this.segmentIndexLimit = properties.getInt("sample.writer.segment-index-limit");
        this.statusIndexLimit = properties.getInt("sample.writer.status-index-limit");
        this.campaignCandidateLimit = properties.getInt("sample.writer.campaign-candidate-limit");
        this.projections = CustomerCacheMaterializerProjectionRegistry.create(
                this,
                cache,
                projectionSettings,
                batchSize);
    }

    public List<String> projectionNames() {
        return List.copyOf(projections.projectionNames());
    }

    public ProjectionRefreshResult refreshProjection(String projectionName, String lockName, long lockTtlMillis) {
        return projections.refresh(projectionName, lockName, lockTtlMillis);
    }

    SnapshotResult writeDetail(ProjectionTarget projection) throws Exception {
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot ->
                repository.forEachCustomerPage(pageSize, page -> {
                    for (SampleCustomer customer : page) {
                        byte[] detail = jsonWriter.customerDetail(customer);
                        snapshot.putById(customer.id(), detail);
                        snapshot.putIndex("customer-no", customer.customerNo(), detail);
                    }
                }));
    }

    SnapshotResult writeSegment(ProjectionTarget projection) throws Exception {
        List<String> segments = repository.findSegments();
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot -> {
            for (String segment : segments) {
                List<SampleCustomer> customers = repository.findCustomersBySegment(segment, segmentIndexLimit);
                snapshot.putIndex("segment", segment, jsonWriter.customerSummaries("segment", segment, customers));
            }
        });
    }

    SnapshotResult writeStatus(ProjectionTarget projection) throws Exception {
        List<String> statuses = repository.findStatuses();
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot -> {
            for (String status : statuses) {
                List<SampleCustomer> customers = repository.findCustomersByStatus(status, statusIndexLimit);
                snapshot.putIndex("status", status, jsonWriter.customerSummaries("status", status, customers));
            }
        });
    }

    SnapshotResult writeCampaign(ProjectionTarget projection) throws Exception {
        List<SampleCustomer> activeCustomers = repository.findCustomersByStatus("active", campaignCandidateLimit);
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot ->
                snapshot.putIndex("campaign", "retention", jsonWriter.campaignCandidates("retention", activeCustomers)));
    }

    SnapshotResult writeMeta(ProjectionTarget projection) throws Exception {
        CustomerCounts counts = repository.countCustomersByStatus();
        List<String> segments = repository.findSegments();
        List<String> statuses = repository.findStatuses();
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot ->
                snapshot.putMeta(jsonWriter.meta(counts, counts.total(), Instant.now(), segments, statuses)));
    }

}
