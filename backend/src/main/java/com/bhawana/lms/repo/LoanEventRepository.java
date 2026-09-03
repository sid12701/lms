package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanEvent;
import com.bhawana.lms.domain.LoanEventType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoanEventRepository {

    /**
     * The feed's ordering key is {@code transaction_id}, an {@code xid8}, and it must stay one.
     *
     * <p>{@code transaction_id} is read out as text because the driver has no Java mapping for
     * {@code xid8} — but the cast is aliased to a <em>different</em> name on purpose. PostgreSQL
     * resolves a bare column name in {@code ORDER BY} to an <em>output</em> column in preference to
     * an input one, so aliasing the text cast back to {@code transaction_id} would silently sort the
     * feed lexicographically while {@link #findCommittedAfter}'s keyset predicate — which sits in
     * {@code WHERE}, where only input columns are visible — went on comparing the number. The two
     * disagree the moment ids differ in width ({@code '997' > '1008'} as text, {@code 997 < 1008} as
     * numbers), and a paging consumer then steps over rows it can never ask for again.
     */
    private static final String EVENT_COLUMNS = """
            id,
            lsp_id,
            event_type,
            aggregate_type,
            aggregate_id,
            loan_application_id,
            payload_json::text AS payload_json,
            occurred_at,
            correlation_id,
            transaction_id::text AS transaction_id_text,
            position
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LoanEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LoanEvent append(
            UUID id,
            UUID lspId,
            LoanEventType eventType,
            String aggregateType,
            String aggregateId,
            UUID loanApplicationId,
            String payloadJson,
            Instant occurredAt,
            String correlationId
    ) {
        String sql = """
                INSERT INTO loan_event (
                    id,
                    lsp_id,
                    event_type,
                    aggregate_type,
                    aggregate_id,
                    loan_application_id,
                    payload_json,
                    occurred_at,
                    correlation_id
                ) VALUES (
                    :id,
                    :lspId,
                    :eventType,
                    :aggregateType,
                    :aggregateId,
                    :loanApplicationId,
                    CAST(:payloadJson AS jsonb),
                    :occurredAt,
                    :correlationId
                )
                RETURNING
                """ + EVENT_COLUMNS;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lspId", lspId)
                .addValue("eventType", eventType.name())
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("loanApplicationId", loanApplicationId)
                .addValue("payloadJson", payloadJson)
                .addValue("occurredAt", Timestamp.from(occurredAt))
                .addValue("correlationId", correlationId);

        return jdbc.queryForObject(sql, parameters, LoanEventRepository::mapEvent);
    }

    /**
     * One page of an LSP's feed, in feed order, starting strictly after the composite cursor and
     * narrowed to {@code eventTypes} — an empty collection meaning every type.
     *
     * <p>Only transactions below the current snapshot's {@code xmin} are served. Postgres assigns
     * both the transaction id and the position <em>before</em> commit, so a consumer paging past a
     * transaction that is still in flight would never see it once it committed. Excluding
     * still-open transactions is what makes the composite cursor safe, and is not optional.
     *
     * <p>The type filter narrows the rows returned; it never changes the ordering key, so a cursor
     * built from a filtered page still names a position in the unfiltered stream. Non-matching rows
     * are skipped over rather than consumed — a partner that widens its filter and rewinds to an
     * older cursor reads them then (ADR 0007).
     *
     * <p>Row-level security already confines the tenant connection to its own rows; the explicit
     * {@code lsp_id} predicate is both a second line of defence and what lets the keyset scan ride
     * {@code idx_loan_event_feed_order}. The type filter rides along as a predicate on that same
     * ordered scan, so it costs no extra ordering work.
     */
    public List<LoanEvent> findCommittedAfter(
            UUID lspId,
            String transactionId,
            long position,
            Collection<LoanEventType> eventTypes,
            int limit
    ) {
        String sql = """
                SELECT
                """ + EVENT_COLUMNS + """
                FROM loan_event
                WHERE lsp_id = :lspId
                  AND transaction_id < pg_snapshot_xmin(pg_current_snapshot())
                  AND (transaction_id, position) > (CAST(:transactionId AS xid8), :position)
                """
                + (eventTypes.isEmpty() ? "" : "  AND event_type IN (:eventTypes)\n")
                + """
                ORDER BY transaction_id, position
                LIMIT :limit
                """;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("lspId", lspId)
                .addValue("transactionId", transactionId)
                .addValue("position", position)
                .addValue("limit", limit);
        if (!eventTypes.isEmpty()) {
            parameters.addValue("eventTypes", eventTypes.stream().map(Enum::name).toList());
        }
        return jdbc.query(sql, parameters, LoanEventRepository::mapEvent);
    }

    private static LoanEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LoanEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("lsp_id", UUID.class),
                LoanEventType.valueOf(resultSet.getString("event_type")),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getObject("loan_application_id", UUID.class),
                resultSet.getString("payload_json"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("correlation_id"),
                resultSet.getString("transaction_id_text"),
                resultSet.getLong("position")
        );
    }
}
