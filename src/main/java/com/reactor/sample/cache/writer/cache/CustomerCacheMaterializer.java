package com.reactor.sample.cache.writer.cache;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter.SnapshotResult;
import com.reactor.sample.cache.writer.config.CacheProjectionSettings;
import com.reactor.sample.cache.writer.config.WriterProperties;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository.CustomerCounts;
import com.reactor.sample.cache.writer.db.SampleCustomer;
import com.reactor.sample.cache.writer.json.CustomerJsonWriter;

import java.time.Instant;
import java.util.List;

public final class CustomerCacheMaterializer {

    private final PostgresCustomerRepository repository;
    private final RustCache cache;
    private final Projection detailProjection;
    private final Projection segmentProjection;
    private final Projection statusProjection;
    private final Projection campaignProjection;
    private final Projection metaProjection;
    private final int pageSize;
    private final int segmentIndexLimit;
    private final int statusIndexLimit;
    private final int campaignCandidateLimit;

    public CustomerCacheMaterializer(PostgresCustomerRepository repository, RustCache cache, WriterProperties properties) {
        this(repository, cache, properties, CacheProjectionSettings.resolveAll(properties));
    }

    public CustomerCacheMaterializer(
            PostgresCustomerRepository repository,
            RustCache cache,
            WriterProperties properties,
            List<CacheProjectionSettings> projectionSettings) {
        this.repository = repository;
        this.cache = cache;
        int batchSize = properties.getInt("sample.writer.snapshot-batch-size");
        this.detailProjection = projection(cache, setting(projectionSettings, "detail"), batchSize);
        this.segmentProjection = projection(cache, setting(projectionSettings, "segment"), batchSize);
        this.statusProjection = projection(cache, setting(projectionSettings, "status"), batchSize);
        this.campaignProjection = projection(cache, setting(projectionSettings, "campaign"), batchSize);
        this.metaProjection = projection(cache, setting(projectionSettings, "meta"), batchSize);
        this.pageSize = properties.getInt("sample.writer.page-size");
        this.segmentIndexLimit = properties.getInt("sample.writer.segment-index-limit");
        this.statusIndexLimit = properties.getInt("sample.writer.status-index-limit");
        this.campaignCandidateLimit = properties.getInt("sample.writer.campaign-candidate-limit");
    }

    public static List<String> projectionNames() {
        return CacheProjectionSettings.projectionNames();
    }

    public RefreshResult refreshProjection(String projectionName, String lockName, long lockTtlMillis) {
        Projection projection = projectionByName(projectionName);
        RefreshResult[] result = new RefreshResult[1];
        boolean ran = cache.locks().runOnceChecked(lockName, lockTtlMillis, lock -> {
            SnapshotResult snapshot = writeProjection(projection);
            lock.ensureValid();
            result[0] = RefreshResult.publishedResult(projection.name(), snapshot.version(), snapshot.writtenKeys());
        });
        return ran ? result[0] : RefreshResult.skippedResult(projection.name());
    }

    private static Projection projection(RustCache cache, CacheProjectionSettings settings, int batchSize) {
        return new Projection(
                settings.name(),
                settings.namespace(),
                settings.effectiveCacheTtlMillis(),
                cache.versionedJsonWriter(settings.namespace(), batchSize));
    }

    private SnapshotResult writeProjection(Projection projection) throws Exception {
        return switch (projection.name()) {
            case "detail" -> writeDetail(projection);
            case "segment" -> writeSegment(projection);
            case "status" -> writeStatus(projection);
            case "campaign" -> writeCampaign(projection);
            case "meta" -> writeMeta(projection);
            default -> throw new IllegalArgumentException("Unsupported projection: " + projection.name());
        };
    }

    private SnapshotResult writeDetail(Projection projection) throws Exception {
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot ->
                repository.forEachCustomerPage(pageSize, page -> {
                    for (SampleCustomer customer : page) {
                        byte[] detail = CustomerJsonWriter.customerDetail(customer);
                        snapshot.putById(customer.id(), detail);
                        snapshot.putIndex("customer-no", customer.customerNo(), detail);
                    }
                }));
    }

    private SnapshotResult writeSegment(Projection projection) throws Exception {
        List<String> segments = repository.findSegments();
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot -> {
            for (String segment : segments) {
                List<SampleCustomer> customers = repository.findCustomersBySegment(segment, segmentIndexLimit);
                snapshot.putIndex("segment", segment, CustomerJsonWriter.customerSummaries("segment", segment, customers));
            }
        });
    }

    private SnapshotResult writeStatus(Projection projection) throws Exception {
        List<String> statuses = repository.findStatuses();
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot -> {
            for (String status : statuses) {
                List<SampleCustomer> customers = repository.findCustomersByStatus(status, statusIndexLimit);
                snapshot.putIndex("status", status, CustomerJsonWriter.customerSummaries("status", status, customers));
            }
        });
    }

    private SnapshotResult writeCampaign(Projection projection) throws Exception {
        List<SampleCustomer> activeCustomers = repository.findCustomersByStatus("active", campaignCandidateLimit);
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot ->
                snapshot.putIndex("campaign", "retention", CustomerJsonWriter.campaignCandidates("retention", activeCustomers)));
    }

    private SnapshotResult writeMeta(Projection projection) throws Exception {
        CustomerCounts counts = repository.countCustomersByStatus();
        List<String> segments = repository.findSegments();
        List<String> statuses = repository.findStatuses();
        return projection.writer().refreshSnapshot(projection.ttlMillis(), snapshot ->
                snapshot.putMeta(CustomerJsonWriter.meta(counts, counts.total(), Instant.now(), segments, statuses)));
    }

    private Projection projectionByName(String projectionName) {
        return switch (projectionName) {
            case "detail" -> detailProjection;
            case "segment" -> segmentProjection;
            case "status" -> statusProjection;
            case "campaign" -> campaignProjection;
            case "meta" -> metaProjection;
            default -> throw new IllegalArgumentException("Unsupported projection: " + projectionName
                    + ". Supported projections: " + projectionNames());
        };
    }

    private static CacheProjectionSettings setting(List<CacheProjectionSettings> settings, String name) {
        return settings.stream()
                .filter(setting -> setting.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing projection setting: " + name));
    }

    private record Projection(
            String name,
            String namespace,
            long ttlMillis,
            VersionedJsonCacheWriter writer) {}

    public record RefreshResult(String projection, boolean published, boolean skippedLocked, String version, int writtenKeys) {

        static RefreshResult skippedResult(String projection) {
            return new RefreshResult(projection, false, true, "", 0);
        }

        static RefreshResult publishedResult(String projection, String version, int writtenKeys) {
            return new RefreshResult(projection, true, false, version, writtenKeys);
        }
    }
}
