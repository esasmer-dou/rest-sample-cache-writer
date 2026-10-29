package com.reactor.sample.cache.writer.db;

import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.sample.cache.writer.json.CustomerJsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sample.writer.jdbc.integration", matches = "true")
class ProjectionQueryLiveTest {
    @Test
    void metadataPreservesCountsAndBoundedOrderedKeysInOneQuery() {
        try (var repository = PostgresCustomerRepository.fromProperties(
                CacheProperties.load("rest-sample-cache-writer.properties"))) {
            var metadata = repository.projectionMetadata();
            assertEquals(repository.countCustomersByStatus(), metadata.counts());
            assertEquals(repository.findSegments(), metadata.segments());
            assertEquals(repository.findStatuses(), metadata.statuses());
            assertTrue(metadata.segments().size() <= 64);
            assertTrue(metadata.statuses().size() <= 32);
        }
    }

    @Test
    void metadataMatchesLegacyForEmptyAndOverLimitKeySets() throws Exception {
        var properties = CacheProperties.load("rest-sample-cache-writer.properties");
        var source = new org.postgresql.ds.PGSimpleDataSource();
        source.setURL(properties.get("sample.db.jdbc-url"));
        source.setUser(properties.get("sample.db.username"));
        source.setPassword(properties.get("sample.db.password"));
        String schema = "metadata_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var admin = source.getConnection(); var sql = admin.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                sql.execute("create table " + schema + ".sample_customers (segment text, status text)");
                source.setCurrentSchema(schema);
                try (var repository = new PostgresCustomerRepository(source, false)) {
                    var empty = repository.projectionMetadata();
                    assertEquals(new com.reactor.sample.model.customer.CustomerCounts(0, 0, 0), empty.counts());
                    assertEquals(java.util.List.of(), empty.segments());
                    assertEquals(java.util.List.of(), empty.statuses());
                    sql.execute("insert into " + schema + ".sample_customers "
                            + "select 'segment-' || n, 'status-' || n from generate_series(1, 80) n");
                    sql.execute("insert into " + schema + ".sample_customers values "
                            + "(null, null), ('', ''), ('  ', '  '), ('Çağrı', 'active'), ('İpek', 'passive')");
                    var full = repository.projectionMetadata();
                    assertEquals(repository.countCustomersByStatus(), full.counts());
                    assertEquals(repository.findSegments(), full.segments());
                    assertEquals(repository.findStatuses(), full.statuses());
                    assertEquals(85, full.counts().total());
                }
            } finally {
                sql.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test
    void groupedCursorPreservesOrderedPayloadsAndPerGroupLimits() {
        try (var repository = PostgresCustomerRepository.fromProperties(
                CacheProperties.load("rest-sample-cache-writer.properties"))) {
            var writer = new CustomerJsonWriter();
            for (int limit : new int[]{1, 2, 1000}) {
                var expected = new LinkedHashMap<String, byte[]>();
                for (String segment : repository.findSegments())
                    expected.put(segment, writer.customerSummaries("segment", segment,
                            repository.findCustomersBySegment(segment, limit)));
                var actual = new LinkedHashMap<String, byte[]>();
                repository.forEachSegment(limit, (key, rows) ->
                        actual.put(key, writer.customerSummaries("segment", key, rows)));
                assertEquals(expected.keySet().stream().toList(), actual.keySet().stream().toList());
                expected.forEach((key, bytes) -> assertArrayEquals(bytes, actual.get(key), key));
                expected.clear(); actual.clear();
                for (String status : repository.findStatuses())
                    expected.put(status, writer.customerSummaries("status", status,
                            repository.findCustomersByStatus(status, limit)));
                repository.forEachStatus(limit, (key, rows) ->
                        actual.put(key, writer.customerSummaries("status", key, rows)));
                assertEquals(expected.keySet().stream().toList(), actual.keySet().stream().toList());
                expected.forEach((key, bytes) -> assertArrayEquals(bytes, actual.get(key), key));
            }
        }
    }

    @Test
    void failedConsumerClosesItsCursorAndReturnsThePoolConnection() {
        try (var repository = PostgresCustomerRepository.fromProperties(
                CacheProperties.load("rest-sample-cache-writer.properties"))) {
            for (int i = 0; i < 4; i++)
                assertThrows(IllegalStateException.class, () -> repository.forEachSegment(1,
                        (key, rows) -> { throw new IllegalArgumentException("consumer failed"); }));
            var keys = new java.util.ArrayList<String>();
            repository.forEachSegment(1, (key, rows) -> keys.add(key));
            assertEquals(repository.findSegments(), keys);
            assertTrue(repository.countCustomersByStatus().total() > 0);
        }
    }

    @Test
    void boundedProbeUsesAutocommitAndOverflowFallsBackWithoutPartialCallbacks() throws Exception {
        var properties = CacheProperties.load("rest-sample-cache-writer.properties");
        var source = new TrackingSource();
        source.setURL(properties.get("sample.db.jdbc-url"));
        source.setUser(properties.get("sample.db.username"));
        source.setPassword(properties.get("sample.db.password"));
        String schema = "group_probe_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var admin = source.getConnection(); var sql = admin.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                sql.execute("create table " + schema + ".sample_customers (id bigint primary key, "
                        + "customer_no text, full_name text, segment text, email text, status text, "
                        + "created_at timestamptz default now(), updated_at timestamptz default now())");
                source.setCurrentSchema(schema);
                try (var repository = new PostgresCustomerRepository(source, false)) {
                    for (int size : new int[]{0, 5, 64, 65, 130}) {
                        sql.execute("truncate " + schema + ".sample_customers");
                        sql.execute("insert into " + schema + ".sample_customers "
                                + "(id,customer_no,full_name,segment,email,status) "
                                + "select n, 'C-'||n, 'Çağrı '||n, 'standard', 'a@b', 'active' "
                                + "from generate_series(1," + size + ") n");
                        var actual = new java.util.ArrayList<Long>();
                        var calls = new java.util.concurrent.atomic.AtomicInteger();
                        repository.forEachSegment(1000, (key, rows) -> {
                            calls.incrementAndGet();
                            try {
                                assertEquals(size <= 64, source.last.getAutoCommit());
                            } catch (java.sql.SQLException failed) { throw new AssertionError(failed); }
                            rows.forEach(row -> actual.add(row.id()));
                        });
                        assertEquals(size == 0 ? 0 : 1, calls.get(), "Partial probe leaked a callback");
                        assertEquals(java.util.stream.LongStream.rangeClosed(1, size).boxed().toList(), actual);
                        assertTrue(source.last.isClosed(), "Connection leaked after grouped read");
                        if (size > 0) {
                            assertThrows(IllegalStateException.class, () -> repository.forEachSegment(1000,
                                    (key, rows) -> { throw new IllegalArgumentException("callback failed"); }));
                            assertTrue(source.last.isClosed(), "Callback failure leaked a connection");
                        }
                    }
                }
            } finally {
                sql.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static final class TrackingSource extends org.postgresql.ds.PGSimpleDataSource {
        java.sql.Connection last;
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
            return last = super.getConnection();
        }
    }
}
