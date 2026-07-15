package com.bhawana.lms.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresAdvisoryLockSupport {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private volatile Boolean postgresDatabase;

    public PostgresAdvisoryLockSupport(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public boolean tryAcquire(long lockId) {
        if (!postgresDatabase()) {
            return true;
        }
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_lock(?)",
                Boolean.class,
                lockId
        );
        return Boolean.TRUE.equals(acquired);
    }

    public void release(long lockId) {
        if (!postgresDatabase()) {
            return;
        }
        jdbcTemplate.queryForObject("select pg_advisory_unlock(?)", Boolean.class, lockId);
    }

    private boolean postgresDatabase() {
        Boolean cached = postgresDatabase;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (postgresDatabase == null) {
                postgresDatabase = detectPostgres();
            }
            return postgresDatabase;
        }
    }

    private boolean detectPostgres() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            return product.contains("postgres");
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to detect database product for advisory locks.", exception);
        }
    }
}
