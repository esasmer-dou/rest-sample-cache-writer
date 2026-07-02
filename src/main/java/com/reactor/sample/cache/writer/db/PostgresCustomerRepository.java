package com.reactor.sample.cache.writer.db;

import com.reactor.sample.cache.writer.config.WriterProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class PostgresCustomerRepository implements AutoCloseable {

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

    private final HikariDataSource dataSource;
    private final boolean schemaInit;
    private volatile boolean initialized;

    private PostgresCustomerRepository(HikariDataSource dataSource, boolean schemaInit) {
        this.dataSource = dataSource;
        this.schemaInit = schemaInit;
    }

    public static PostgresCustomerRepository fromProperties(WriterProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(properties.get("sample.db.pool-name"));
        config.setDriverClassName(properties.get("sample.db.driver-class-name"));
        config.setJdbcUrl(properties.get("sample.db.jdbc-url"));
        config.setUsername(properties.get("sample.db.username"));
        config.setPassword(properties.getOptional("sample.db.password"));
        config.setMaximumPoolSize(properties.getInt("sample.db.maximum-pool-size"));
        config.setMinimumIdle(properties.getInt("sample.db.minimum-idle"));
        config.setConnectionTimeout(properties.getLong("sample.db.connection-timeout-ms"));
        config.setValidationTimeout(properties.getLong("sample.db.validation-timeout-ms"));
        config.setIdleTimeout(properties.getLong("sample.db.idle-timeout-ms"));
        config.setMaxLifetime(properties.getLong("sample.db.max-lifetime-ms"));
        config.setLeakDetectionThreshold(properties.getLong("sample.db.leak-detection-threshold-ms"));
        config.setInitializationFailTimeout(properties.getLong("sample.db.initialization-fail-timeout-ms"));
        config.setAutoCommit(properties.getBoolean("sample.db.auto-commit"));
        config.setReadOnly(properties.getBoolean("sample.db.read-only"));
        config.setRegisterMbeans(properties.getBoolean("sample.db.register-mbeans"));
        config.addDataSourceProperty("ApplicationName", properties.get("sample.db.postgresql.application-name"));
        return new PostgresCustomerRepository(new HikariDataSource(config), properties.getBoolean("sample.db.schema-init"));
    }

    public void forEachCustomerPage(int pageSize, Consumer<List<SampleCustomer>> pageConsumer) {
        ensureInitialized();
        int boundedPageSize = Math.max(1, Math.min(pageSize, 5_000));
        long lastSeenId = 0;
        while (true) {
            List<SampleCustomer> page = findCustomerPage(boundedPageSize, lastSeenId);
            if (page.isEmpty()) {
                return;
            }
            pageConsumer.accept(page);
            if (page.size() < boundedPageSize) {
                return;
            }
            lastSeenId = page.get(page.size() - 1).id();
        }
    }

    public List<String> findSegments() {
        ensureInitialized();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SEGMENTS);
             ResultSet resultSet = statement.executeQuery()) {
            List<String> segments = new ArrayList<>();
            while (resultSet.next()) {
                String segment = resultSet.getString("segment");
                if (segment != null && !segment.isBlank()) {
                    segments.add(segment);
                }
            }
            return segments;
        } catch (Exception e) {
            throw new IllegalStateException("Find customer segments failed", e);
        }
    }

    public List<SampleCustomer> findCustomersBySegment(String segment, int limit) {
        ensureInitialized();
        int boundedLimit = Math.max(1, Math.min(limit, 1_000));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_CUSTOMERS_BY_SEGMENT)) {
            statement.setString(1, segment);
            statement.setInt(2, boundedLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SampleCustomer> customers = new ArrayList<>();
                while (resultSet.next()) {
                    customers.add(toCustomer(resultSet));
                }
                return customers;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Find customers by segment failed", e);
        }
    }

    public List<String> findStatuses() {
        ensureInitialized();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_STATUSES);
             ResultSet resultSet = statement.executeQuery()) {
            List<String> statuses = new ArrayList<>();
            while (resultSet.next()) {
                String status = resultSet.getString("status");
                if (status != null && !status.isBlank()) {
                    statuses.add(status);
                }
            }
            return statuses;
        } catch (Exception e) {
            throw new IllegalStateException("Find customer statuses failed", e);
        }
    }

    public List<SampleCustomer> findCustomersByStatus(String status, int limit) {
        ensureInitialized();
        int boundedLimit = Math.max(1, Math.min(limit, 1_000));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_CUSTOMERS_BY_STATUS)) {
            statement.setString(1, status);
            statement.setInt(2, boundedLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SampleCustomer> customers = new ArrayList<>();
                while (resultSet.next()) {
                    customers.add(toCustomer(resultSet));
                }
                return customers;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Find customers by status failed", e);
        }
    }

    public CustomerCounts countCustomersByStatus() {
        ensureInitialized();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_CUSTOMER_COUNTS);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return new CustomerCounts(0, 0, 0);
            }
            return new CustomerCounts(resultSet.getInt("total"), resultSet.getInt("active"), resultSet.getInt("passive"));
        } catch (Exception e) {
            throw new IllegalStateException("Count customers failed", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private List<SampleCustomer> findCustomerPage(int limit, long lastSeenId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_CUSTOMERS_PAGE)) {
            statement.setLong(1, lastSeenId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SampleCustomer> customers = new ArrayList<>(limit);
                while (resultSet.next()) {
                    customers.add(toCustomer(resultSet));
                }
                return customers;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Find customer page failed", e);
        }
    }

    private void ensureInitialized() {
        if (!schemaInit || initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
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
                initialized = true;
            } catch (Exception e) {
                throw new IllegalStateException("PostgreSQL sample schema init failed", e);
            }
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
