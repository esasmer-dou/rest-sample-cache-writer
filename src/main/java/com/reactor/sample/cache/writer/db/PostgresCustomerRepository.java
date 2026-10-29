package com.reactor.sample.cache.writer.db;

import com.reactor.rust.cache.jdbc.HikariDataSources;
import com.reactor.rust.cache.jdbc.JdbcRepository;
import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.sample.model.customer.CustomerCounts;
import com.reactor.sample.model.customer.CustomerCountsJdbcMapper;
import com.reactor.sample.model.customer.SampleCustomer;
import com.reactor.sample.model.customer.SampleCustomerJdbcMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

public final class PostgresCustomerRepository extends JdbcRepository {

    private static final String SELECT_CUSTOMERS_PAGE = """
            select id, customer_no, full_name, segment, email, status, created_at, updated_at
            from sample_customers
            where id > ?
            order by id
            limit ?
            """;
    private static final String SELECT_SEGMENTS = """
            select distinct segment
            from sample_customers
            order by segment
            limit 64
            """;
    private static final String SELECT_CUSTOMERS_BY_SEGMENT = """
            select id, customer_no, full_name, segment, email, status, created_at, updated_at
            from sample_customers
            where segment = ?
            order by id
            limit ?
            """;
    private static final String SELECT_STATUSES = """
            select distinct status
            from sample_customers
            order by status
            limit 32
            """;
    private static final String SELECT_CUSTOMERS_BY_STATUS = """
            select id, customer_no, full_name, segment, email, status, created_at, updated_at
            from sample_customers
            where status = ?
            order by id
            limit ?
            """;
    private static final String SELECT_CUSTOMER_COUNTS = """
            select
              count(*) as total,
              sum(case when status = 'active' then 1 else 0 end) as active,
              sum(case when status = 'passive' then 1 else 0 end) as passive
            from sample_customers
            """;
    private static final String SELECT_PROJECTION_METADATA = """
            select count(*) as total,
              sum(case when status = 'active' then 1 else 0 end) as active,
              sum(case when status = 'passive' then 1 else 0 end) as passive,
              array(select distinct segment from sample_customers order by segment limit 64) as segments,
              array(select distinct status from sample_customers order by status limit 32) as statuses
            from sample_customers
            """;
    private static final String SELECT_SEGMENT_GROUPS = """
            select c.id, c.customer_no, c.full_name, c.segment, c.email, c.status, c.created_at, c.updated_at
            from (select distinct segment from sample_customers order by segment limit 64) g
            cross join lateral (
              select * from sample_customers where segment = g.segment order by id limit ?
            ) c
            where g.segment is not null and btrim(g.segment) <> ''
            order by g.segment, c.id
            """;
    private static final String SELECT_STATUS_GROUPS = """
            select c.id, c.customer_no, c.full_name, c.segment, c.email, c.status, c.created_at, c.updated_at
            from (select distinct status from sample_customers order by status limit 32) g
            cross join lateral (
              select * from sample_customers where status = g.status order by id limit ?
            ) c
            where g.status is not null and btrim(g.status) <> ''
            order by g.status, c.id
            """;

    private static final GroupQuery SEGMENT_GROUP_QUERY = GroupQuery.bounded(SELECT_SEGMENT_GROUPS, 64, "segment,id");
    private static final GroupQuery STATUS_GROUP_QUERY = GroupQuery.bounded(SELECT_STATUS_GROUPS, 64, "status,id");

    private PostgresCustomerRepository(CacheProperties properties) {
        this(
                HikariDataSources.create(properties.asProperties(), "sample.db"),
                properties.getBoolean("sample.db.schema-init"));
    }

    PostgresCustomerRepository(javax.sql.DataSource dataSource, boolean schemaInit) {
        super(dataSource, schemaInit);
    }

    public static PostgresCustomerRepository fromProperties(CacheProperties properties) {
        return new PostgresCustomerRepository(properties);
    }

    public void forEachCustomerPage(int pageSize, Consumer<List<SampleCustomer>> pageConsumer) {
        int boundedPageSize = Math.max(1, Math.min(pageSize, 5_000));
        forEachIdPage(
                boundedPageSize,
                boundedPageSize,
                lastSeenId -> findCustomerPage(boundedPageSize, lastSeenId),
                SampleCustomer::id,
                pageConsumer);
    }

    public List<String> findSegments() {
        return queryStrings("Find customer segments", SELECT_SEGMENTS, "segment");
    }

    public List<SampleCustomer> findCustomersBySegment(String segment, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 1_000));
        return query("Find customers by segment", SELECT_CUSTOMERS_BY_SEGMENT, statement -> {
            statement.setString(1, segment);
            statement.setInt(2, boundedLimit);
        }, SampleCustomerJdbcMapper::map);
    }

    public List<String> findStatuses() {
        return queryStrings("Find customer statuses", SELECT_STATUSES, "status");
    }

    public void forEachSegment(int limit, BiConsumer<String, List<SampleCustomer>> consumer) {
        int bounded = Math.max(1, Math.min(limit, 1_000));
        forEachQueryGroup("Read segment projection", SEGMENT_GROUP_QUERY, s -> s.setInt(1, bounded),
                SampleCustomerJdbcMapper::map, SampleCustomer::segment, 64, bounded, consumer);
    }

    public void forEachStatus(int limit, BiConsumer<String, List<SampleCustomer>> consumer) {
        int bounded = Math.max(1, Math.min(limit, 1_000));
        forEachQueryGroup("Read status projection", STATUS_GROUP_QUERY, s -> s.setInt(1, bounded),
                SampleCustomerJdbcMapper::map, SampleCustomer::status, 32, bounded, consumer);
    }

    public List<SampleCustomer> findCustomersByStatus(String status, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 1_000));
        return query("Find customers by status", SELECT_CUSTOMERS_BY_STATUS, statement -> {
            statement.setString(1, status);
            statement.setInt(2, boundedLimit);
        }, SampleCustomerJdbcMapper::map);
    }

    public CustomerCounts countCustomersByStatus() {
        return queryOne(
                "Count customers",
                SELECT_CUSTOMER_COUNTS,
                SqlBinder.none(),
                CustomerCountsJdbcMapper::map,
                new CustomerCounts(0, 0, 0));
    }

    /** Counts and bounded key lists share one database snapshot and one round trip. */
    public ProjectionMetadata projectionMetadata() {
        return queryOne("Read projection metadata", SELECT_PROJECTION_METADATA, SqlBinder.none(),
                row -> new ProjectionMetadata(CustomerCountsJdbcMapper.map(row),
                        strings(row, "segments"), strings(row, "statuses")),
                new ProjectionMetadata(new CustomerCounts(0, 0, 0), List.of(), List.of()));
    }

    private static List<String> strings(ResultSet row, String column) throws SQLException {
        java.sql.Array array = row.getArray(column);
        if (array == null) return List.of();
        try {
            Object[] values = (Object[]) array.getArray();
            List<String> strings = new ArrayList<>(values.length);
            for (Object value : values) {
                if (value instanceof String text && !text.isBlank()) strings.add(text);
            }
            return strings;
        } finally {
            array.free();
        }
    }

    public record ProjectionMetadata(CustomerCounts counts, List<String> segments, List<String> statuses) {}

    private List<SampleCustomer> findCustomerPage(int limit, long lastSeenId) {
        return query("Find customer page", SELECT_CUSTOMERS_PAGE, statement -> {
            statement.setLong(1, lastSeenId);
            statement.setInt(2, limit);
        }, SampleCustomerJdbcMapper::map);
    }

    @Override
    protected void initializeSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists sample_customers (
                      id bigserial primary key,
                      customer_no varchar(32) not null unique,
                      full_name varchar(128) not null,
                      segment varchar(32) not null,
                      email varchar(160) not null default '',
                      status varchar(32) not null default 'active',
                      created_at timestamptz not null default now(),
                      updated_at timestamptz not null default now()
                    )
                    """);
            statement.executeUpdate("""
                    create index if not exists idx_sample_customers_segment
                    on sample_customers(segment, id)
                    """);
            statement.executeUpdate("""
                    create index if not exists idx_sample_customers_status
                    on sample_customers(status, id)
                    """);
            statement.executeUpdate("""
                    insert into sample_customers (customer_no, full_name, segment, email, status)
                    values
                      ('CUST-1001', 'Mustafa Korkmaz', 'pilot', 'mustafa.korkmaz@example.com', 'active'),
                      ('CUST-1002', 'Ayşe Demir', 'enterprise', 'ayse.demir@example.com', 'active'),
                      ('CUST-1003', 'Mehmet Çelik', 'standard', 'mehmet.celik@example.com', 'passive'),
                      ('CUST-1004', 'Zeynep Şahin', 'enterprise', 'zeynep.sahin@example.com', 'active'),
                      ('CUST-1005', 'Çağrı Özkan', 'pilot', 'cagri.ozkan@example.com', 'active')
                    on conflict (customer_no) do nothing
                    """);
        }
    }

}
