package com.bhawana.lms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresAdvisoryLockSupport {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAdvisoryLockSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryAcquire(long lockId) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_lock(?)",
                Boolean.class,
                lockId
        );
        return Boolean.TRUE.equals(acquired);
    }

    public void release(long lockId) {
        jdbcTemplate.queryForObject("select pg_advisory_unlock(?)", Boolean.class, lockId);
    }
}
