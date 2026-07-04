package com.reactor.sample.cache.writer.db;

import com.reactor.rust.cache.jdbc.HikariDataSources;
import com.reactor.rust.cache.jdbc.JdbcRepository;
import com.reactor.sample.cache.writer.config.WriterProperties;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.function.Consumer;

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

    private PostgresCustomerRepository(WriterProperties properties) {
        super(
                HikariDataSources.create(properties.asProperties(), "sample.db"),
                properties.getBoolean("sample.db.schema-init"));
    }

    public static PostgresCustomerRepository fromProperties(WriterProperties properties) {
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
        }, PostgresCustomerRepository::toCustomer);
    }

    public List<String> findStatuses() {
        return queryStrings("Find customer statuses", SELECT_STATUSES, "status");
    }

    public List<SampleCustomer> findCustomersByStatus(String status, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 1_000));
        return query("Find customers by status", SELECT_CUSTOMERS_BY_STATUS, statement -> {
            statement.setString(1, status);
            statement.setInt(2, boundedLimit);
        }, PostgresCustomerRepository::toCustomer);
    }

    public CustomerCounts countCustomersByStatus() {
        return queryOne(
                "Count customers",
                SELECT_CUSTOMER_COUNTS,
                SqlBinder.none(),
                row -> new CustomerCounts(row.getInt("total"), row.getInt("active"), row.getInt("passive")),
                new CustomerCounts(0, 0, 0));
    }

    private List<SampleCustomer> findCustomerPage(int limit, long lastSeenId) {
        return query("Find customer page", SELECT_CUSTOMERS_PAGE, statement -> {
            statement.setLong(1, lastSeenId);
            statement.setInt(2, limit);
        }, PostgresCustomerRepository::toCustomer);
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

    private static SampleCustomer toCustomer(ResultSet row) throws Exception {
        return new SampleCustomer(
                row.getLong("id"),
                row.getString("customer_no"),
                row.getString("full_name"),
                row.getString("segment"),
                row.getString("email"),
                row.getString("status"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()
        );
    }

    public record CustomerCounts(int total, int active, int passive) {
    }
}
