package com.reactor.sample.cache.writer.cache;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter.SnapshotResult;
import com.reactor.sample.cache.writer.config.WriterProperties;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository.CustomerCounts;
import com.reactor.sample.cache.writer.db.SampleCustomer;
import com.reactor.sample.cache.writer.json.CustomerJsonWriter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class CustomerCacheMaterializer {

    private final PostgresCustomerRepository repository;
    private final RustCache cache;
    private final Projection detailProjection;
    private final Projection segmentProjection;
    private final Projection statusProjection;
    private final Projection campaignProjection;
    private final Projection metaProjection;
    private final String lockName;
    private final long lockTtlMillis;
    private final int pageSize;
    private final int segmentIndexLimit;
    private final int statusIndexLimit;
    private final int campaignCandidateLimit;

    public CustomerCacheMaterializer(PostgresCustomerRepository repository, RustCache cache, WriterProperties properties) {
        this.repository = repository;
        this.cache = cache;
        int batchSize = properties.getInt("sample.writer.snapshot-batch-size");
        this.detailProjection = projection(cache, properties, "detail", batchSize);
        this.segmentProjection = projection(cache, properties, "segment", batchSize);
        this.statusProjection = projection(cache, properties, "status", batchSize);
        this.campaignProjection = projection(cache, properties, "campaign", batchSize);
        this.metaProjection = projection(cache, properties, "meta", batchSize);
        this.lockName = properties.get("sample.writer.lock-name");
        this.lockTtlMillis = properties.getLong("sample.writer.lock-ttl-ms");
        this.pageSize = properties.getInt("sample.writer.page-size");
        this.segmentIndexLimit = properties.getInt("sample.writer.segment-index-limit");
        this.statusIndexLimit = properties.getInt("sample.writer.status-index-limit");
        this.campaignCandidateLimit = properties.getInt("sample.writer.campaign-candidate-limit");
    }

    public RefreshResult refresh() {
        RefreshResult[] result = new RefreshResult[1];
        boolean ran = cache.locks().runOnceChecked(lockName, lockTtlMillis, lock -> {
            PublishedSnapshots published = new PublishedSnapshots();
            AtomicInteger rowCount = new AtomicInteger();

            SnapshotResult detailResult = detailProjection.writer().refreshSnapshot(detailProjection.ttlMillis(), snapshot ->
                    repository.forEachCustomerPage(pageSize, page -> {
                        for (SampleCustomer customer : page) {
                            byte[] detail = CustomerJsonWriter.customerDetail(customer);
                            snapshot.putById(customer.id(), detail);
                            snapshot.putIndex("customer-no", customer.customerNo(), detail);
                            rowCount.incrementAndGet();
                        }
                    }));
            published.add(detailProjection.name(), detailResult);
            lock.ensureValid();

            List<String> segments = repository.findSegments();
            SnapshotResult segmentResult = segmentProjection.writer().refreshSnapshot(segmentProjection.ttlMillis(), snapshot -> {
                for (String segment : segments) {
                    List<SampleCustomer> customers = repository.findCustomersBySegment(segment, segmentIndexLimit);
                    snapshot.putIndex("segment", segment, CustomerJsonWriter.customerSummaries("segment", segment, customers));
                }
            });
            published.add(segmentProjection.name(), segmentResult);
            lock.ensureValid();

            List<String> statuses = repository.findStatuses();
            SnapshotResult statusResult = statusProjection.writer().refreshSnapshot(statusProjection.ttlMillis(), snapshot -> {
                for (String status : statuses) {
                    List<SampleCustomer> customers = repository.findCustomersByStatus(status, statusIndexLimit);
                    snapshot.putIndex("status", status, CustomerJsonWriter.customerSummaries("status", status, customers));
                }
            });
            published.add(statusProjection.name(), statusResult);
            lock.ensureValid();

            List<SampleCustomer> activeCustomers = repository.findCustomersByStatus("active", campaignCandidateLimit);
            SnapshotResult campaignResult = campaignProjection.writer().refreshSnapshot(campaignProjection.ttlMillis(), snapshot ->
                    snapshot.putIndex("campaign", "retention", CustomerJsonWriter.campaignCandidates("retention", activeCustomers)));
            published.add(campaignProjection.name(), campaignResult);
            lock.ensureValid();

            CustomerCounts counts = repository.countCustomersByStatus();
            SnapshotResult metaResult = metaProjection.writer().refreshSnapshot(metaProjection.ttlMillis(), snapshot ->
                    snapshot.putMeta(CustomerJsonWriter.meta(counts, rowCount.get(), Instant.now(), segments, statuses)));
            published.add(metaProjection.name(), metaResult);

            result[0] = published.toResult();
        });
        return ran ? result[0] : RefreshResult.skippedResult();
    }

    private static Projection projection(RustCache cache, WriterProperties properties, String name, int batchSize) {
        String namespace = projectionNamespace(properties, name);
        long ttlMillis = projectionTtlMillis(properties, name);
        return new Projection(
                name,
                namespace,
                ttlMillis,
                cache.versionedJsonWriter(namespace, batchSize));
    }

    private static String projectionNamespace(WriterProperties properties, String name) {
        String specificKey = "sample.writer." + name + ".namespace";
        String specificRuntime = properties.getRuntimeOverride(specificKey);
        if (hasText(specificRuntime)) {
            return specificRuntime;
        }
        String baseRuntime = properties.getRuntimeOverride("sample.writer.namespace");
        if (hasText(baseRuntime)) {
            return baseRuntime + "." + name;
        }
        String specificFile = properties.getFileOptional(specificKey);
        if (hasText(specificFile)) {
            return specificFile;
        }
        return properties.get("sample.writer.namespace") + "." + name;
    }

    private static long projectionTtlMillis(WriterProperties properties, String name) {
        String specificKey = "sample.writer." + name + ".cache-ttl-ms";
        String specificRuntime = properties.getRuntimeOverride(specificKey);
        if (hasText(specificRuntime)) {
            return parsePositiveMillis(specificKey, specificRuntime);
        }
        String baseRuntime = properties.getRuntimeOverride("sample.writer.cache-ttl-ms");
        if (hasText(baseRuntime)) {
            return parsePositiveMillis("sample.writer.cache-ttl-ms", baseRuntime);
        }
        String specificFile = properties.getFileOptional(specificKey);
        if (hasText(specificFile)) {
            return parsePositiveMillis(specificKey, specificFile);
        }
        return properties.getLong("sample.writer.cache-ttl-ms");
    }

    private static long parsePositiveMillis(String key, String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Property must be positive: " + key + "=" + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property must be a long: " + key + "=" + value, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Projection(
            String name,
            String namespace,
            long ttlMillis,
            VersionedJsonCacheWriter writer) {}

    public record RefreshResult(boolean published, boolean skippedLocked, String versions, int writtenKeys) {

        static RefreshResult skippedResult() {
            return new RefreshResult(false, true, "", 0);
        }
    }

    private static final class PublishedSnapshots {
        private final StringBuilder versions = new StringBuilder(128);
        private int writtenKeys;

        void add(String projection, SnapshotResult result) {
            if (!result.published()) {
                return;
            }
            if (!versions.isEmpty()) {
                versions.append(',');
            }
            versions.append(projection).append('=').append(result.version());
            writtenKeys += result.writtenKeys();
        }

        RefreshResult toResult() {
            return new RefreshResult(true, false, versions.toString(), writtenKeys);
        }
    }
}
