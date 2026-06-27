package com.bhawana.lms.seed.synthetic;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bulk audit insert helpers (JDBC batch — no PostgreSQL-specific COPY API). */
final class SyntheticPortfolioAuditWriter {

    private SyntheticPortfolioAuditWriter() {
    }

    static void flushIntakeAudit(JdbcTemplate jdbcTemplate, List<IntakeAuditRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO loan_application_intake_audit (
                    id, loan_application_id, actor_username, correlation_id, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        IntakeAuditRow row = rows.get(index);
                        ps.setObject(1, row.id());
                        ps.setObject(2, row.applicationId());
                        ps.setString(3, row.actor());
                        ps.setString(4, row.correlationId());
                        ps.setString(5, row.payloadJson());
                        ps.setTimestamp(6, Timestamp.from(row.createdAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                }
        );
    }

    static void flushTransitionAudit(JdbcTemplate jdbcTemplate, List<TransitionAuditRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO loan_application_status_transition (
                    id, loan_application_id, from_status, to_status, actor_username, note, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        TransitionAuditRow row = rows.get(index);
                        ps.setObject(1, row.id());
                        ps.setObject(2, row.applicationId());
                        ps.setString(3, row.fromStatus());
                        ps.setString(4, row.toStatus());
                        ps.setString(5, row.actor());
                        ps.setString(6, row.note());
                        ps.setTimestamp(7, Timestamp.from(row.createdAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                }
        );
    }

    static void flushApplicationAudit(JdbcTemplate jdbcTemplate, List<ApplicationAuditRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO loan_application_audit_event (
                    id, loan_application_id, action, actor_username, from_status, to_status, note, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        ApplicationAuditRow row = rows.get(index);
                        ps.setObject(1, row.id());
                        ps.setObject(2, row.applicationId());
                        ps.setString(3, row.action());
                        ps.setString(4, row.actor());
                        ps.setString(5, row.fromStatus());
                        ps.setString(6, row.toStatus());
                        ps.setString(7, row.note());
                        ps.setTimestamp(8, Timestamp.from(row.createdAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                }
        );
    }

    record IntakeAuditRow(
            UUID id,
            UUID applicationId,
            String actor,
            String correlationId,
            String payloadJson,
            java.time.Instant createdAt
    ) {
    }

    record TransitionAuditRow(
            UUID id,
            UUID applicationId,
            String fromStatus,
            String toStatus,
            String actor,
            String note,
            java.time.Instant createdAt
    ) {
    }

    record ApplicationAuditRow(
            UUID id,
            UUID applicationId,
            String action,
            String actor,
            String fromStatus,
            String toStatus,
            String note,
            java.time.Instant createdAt
    ) {
    }
}
