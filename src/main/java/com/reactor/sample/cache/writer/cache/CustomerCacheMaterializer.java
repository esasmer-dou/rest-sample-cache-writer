package com.reactor.sample.cache.writer.cache;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.versioned.VersionedJsonCache;
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
    private final VersionedJsonCache customerCache;
    private final String lockName;
    private final long lockTtlMillis;
    private final long cacheTtlMillis;
    private final int pageSize;
    private final int segmentIndexLimit;
    private final int statusIndexLimit;
    private final int campaignCandidateLimit;

    public CustomerCacheMaterializer(PostgresCustomerRepository repository, RustCache cache, WriterProperties properties) {
        this.repository = repository;
        this.customerCache = cache.versionedJson(properties.get("sample.writer.namespace"));
        this.lockName = properties.get("sample.writer.lock-name");
        this.lockTtlMillis = properties.getLong("sample.writer.lock-ttl-ms");
        this.cacheTtlMillis = properties.getLong("sample.writer.cache-ttl-ms");
        this.pageSize = properties.getInt("sample.writer.page-size");
        this.segmentIndexLimit = properties.getInt("sample.writer.segment-index-limit");
        this.statusIndexLimit = properties.getInt("sample.writer.status-index-limit");
        this.campaignCandidateLimit = properties.getInt("sample.writer.campaign-candidate-limit");
    }

    public SnapshotResult refresh() {
        return customerCache.writer().refreshSnapshotWithLock(lockName, lockTtlMillis, cacheTtlMillis, snapshot -> {
            AtomicInteger rowCount = new AtomicInteger();
            repository.forEachCustomerPage(pageSize, page -> {
                for (SampleCustomer customer : page) {
                    byte[] detail = CustomerJsonWriter.customerDetail(customer);
                    snapshot.putById(customer.id(), detail);
                    snapshot.putIndex("customer-no", customer.customerNo(), detail);
                    rowCount.incrementAndGet();
                }
            });

            List<String> segments = repository.findSegments();
            for (String segment : segments) {
                List<SampleCustomer> customers = repository.findCustomersBySegment(segment, segmentIndexLimit);
                snapshot.putIndex("segment", segment, CustomerJsonWriter.customerSummaries("segment", segment, customers));
            }

            List<String> statuses = repository.findStatuses();
            for (String status : statuses) {
                List<SampleCustomer> customers = repository.findCustomersByStatus(status, statusIndexLimit);
                snapshot.putIndex("status", status, CustomerJsonWriter.customerSummaries("status", status, customers));
            }

            List<SampleCustomer> activeCustomers = repository.findCustomersByStatus("active", campaignCandidateLimit);
            snapshot.putIndex("campaign", "retention", CustomerJsonWriter.campaignCandidates("retention", activeCustomers));

            CustomerCounts counts = repository.countCustomersByStatus();
            snapshot.putMeta(CustomerJsonWriter.meta(counts, rowCount.get(), Instant.now(), segments, statuses));
        });
    }
}
