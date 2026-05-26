package com.bhawana.lms.repo;

import com.bhawana.lms.service.AuditExplorerQuery;
import com.bhawana.lms.service.AuditExplorerQuery.AuditStream;
import com.bhawana.lms.common.web.PagedResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native UNION ALL search across the four audit streams.
 *
 * <p>Gap #3 — Unified cross-domain audit search. Each enabled stream
 * contributes one SELECT branch with filters pushed down; the outer query
 * orders by {@code occurred_at DESC} and applies {@code OFFSET}/{@code LIMIT}.
 * Total-count is computed via a separate {@code COUNT(*)} wrap when the
 * caller opts in (paginationDetails=true).
 */
@Repository
public class AuditExplorerRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditExplorerRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public PagedResult<UnifiedAuditEventRow> search(AuditExplorerQuery query) {
        Set<AuditStream> effectiveStreams = effectiveStreams(query);
        if (effectiveStreams.isEmpty()) {
            return new PagedResult<>(List.of(), query.includePaginationDetails() ? 0L : -1L, query.offset(), query.limit());
        }

        Map<String, Object> parameters = new HashMap<>();
        String unionSql = buildUnionSql(effectiveStreams, query, parameters);

        String pageSql = "select * from (" + unionSql + ") u order by u.occurred_at desc, u.native_id desc"
                + " limit :__limit offset :__offset";
        parameters.put("__offset", query.offset());
        parameters.put("__limit", query.limit());

        List<UnifiedAuditEventRow> rows = jdbc.query(pageSql, new MapSqlParameterSource(parameters),
                (rs, rowNum) -> new UnifiedAuditEventRow(
                        rs.getString("stream"),
                        toUuid(rs.getObject("native_id")),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor_username"),
                        rs.getString("correlation_id"),
                        toNullableUuid(rs.getObject("loan_application_id")),
                        toNullableUuid(rs.getObject("borrower_id")),
                        toNullableUuid(rs.getObject("lsp_id")),
                        toNullableUuid(rs.getObject("product_id")),
                        rs.getString("action"),
                        rs.getString("summary"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("reason_code"),
                        rs.getString("payload_json"),
                        rs.getString("document_types")
                ));

        long totalCount;
        if (query.includePaginationDetails()) {
            String countSql = "select count(*) from (" + unionSql + ") u";
            Map<String, Object> countParams = new HashMap<>(parameters);
            countParams.remove("__offset");
            countParams.remove("__limit");
            Long count = jdbc.queryForObject(countSql, new MapSqlParameterSource(countParams), Long.class);
            totalCount = count == null ? 0L : count;
        } else {
            totalCount = -1L;
        }

        return new PagedResult<>(rows, totalCount, query.offset(), query.limit());
    }

    private Set<AuditStream> effectiveStreams(AuditExplorerQuery query) {
        Set<AuditStream> active = EnumSet.copyOf(query.streams());

        // PRODUCT branch carries no loan_application/borrower/lsp identity — if
        // any of those filters are set, exclude PRODUCT entirely.
        if (query.loanApplicationId() != null || query.borrowerId() != null || query.lspId() != null) {
            active.remove(AuditStream.PRODUCT);
        }
        // productId filter only makes sense on PRODUCT — exclude every other
        // branch in that case.
        if (query.productId() != null) {
            active.retainAll(Set.of(AuditStream.PRODUCT));
        }
        return active;
    }

    private String buildUnionSql(
            Set<AuditStream> streams,
            AuditExplorerQuery query,
            Map<String, Object> parameters
    ) {
        // Common filter parameters — registered once and reused per branch.
        parameters.put("__actorUsername", query.actorUsername());
        parameters.put("__lspId", query.lspId());
        parameters.put("__loanApplicationId", query.loanApplicationId());
        parameters.put("__borrowerId", query.borrowerId());
        parameters.put("__productId", query.productId());
        parameters.put("__since", query.since());
        parameters.put("__until", query.until());

        List<String> branches = new ArrayList<>();

        if (streams.contains(AuditStream.APPLICATION)) {
            branches.add("""
                    select
                      cast('APPLICATION' as varchar(32)) as stream,
                      e.id as native_id,
                      e.created_at as occurred_at,
                      cast(e.actor_username as varchar(255)) as actor_username,
                      cast(e.correlation_id as varchar(128)) as correlation_id,
                      e.loan_application_id as loan_application_id,
                      la.borrower_id as borrower_id,
                      la.lsp_id as lsp_id,
                      cast(null as varchar(64)) as product_id,
                      cast(e.action as varchar(64)) as action,
                      cast(e.note as varchar(500)) as summary,
                      cast(e.from_status as varchar(64)) as from_status,
                      cast(e.to_status as varchar(64)) as to_status,
                      cast(e.reason_code as varchar(64)) as reason_code,
                      cast(null as text) as payload_json,
                      cast(null as varchar(500)) as document_types
                    from loan_application_audit_event e
                    join loan_application la on la.id = e.loan_application_id
                    where (:__actorUsername is null or e.actor_username = :__actorUsername)
                      and (:__lspId is null or la.lsp_id = :__lspId)
                      and (:__loanApplicationId is null or e.loan_application_id = :__loanApplicationId)
                      and (:__borrowerId is null or la.borrower_id = :__borrowerId)
                      and (:__since is null or e.created_at >= :__since)
                      and (:__until is null or e.created_at <= :__until)
                    """);
        }

        if (streams.contains(AuditStream.INTAKE)) {
            branches.add("""
                    select
                      cast('INTAKE' as varchar(32)) as stream,
                      i.id as native_id,
                      i.created_at as occurred_at,
                      cast(i.actor_username as varchar(255)) as actor_username,
                      cast(i.correlation_id as varchar(128)) as correlation_id,
                      i.loan_application_id as loan_application_id,
                      la.borrower_id as borrower_id,
                      la.lsp_id as lsp_id,
                      cast(null as varchar(64)) as product_id,
                      cast(null as varchar(64)) as action,
                      cast(null as varchar(500)) as summary,
                      cast(null as varchar(64)) as from_status,
                      cast(null as varchar(64)) as to_status,
                      cast(null as varchar(64)) as reason_code,
                      cast(i.payload_json as text) as payload_json,
                      cast(null as varchar(500)) as document_types
                    from loan_application_intake_audit i
                    join loan_application la on la.id = i.loan_application_id
                    where (:__actorUsername is null or i.actor_username = :__actorUsername)
                      and (:__lspId is null or la.lsp_id = :__lspId)
                      and (:__loanApplicationId is null or i.loan_application_id = :__loanApplicationId)
                      and (:__borrowerId is null or la.borrower_id = :__borrowerId)
                      and (:__since is null or i.created_at >= :__since)
                      and (:__until is null or i.created_at <= :__until)
                    """);
        }

        if (streams.contains(AuditStream.DOCUMENT_ACCESS)) {
            branches.add("""
                    select
                      cast('DOCUMENT_ACCESS' as varchar(32)) as stream,
                      d.id as native_id,
                      d.created_at as occurred_at,
                      cast(d.actor_username as varchar(255)) as actor_username,
                      cast(d.correlation_id as varchar(128)) as correlation_id,
                      d.loan_application_id as loan_application_id,
                      la.borrower_id as borrower_id,
                      la.lsp_id as lsp_id,
                      cast(null as varchar(64)) as product_id,
                      cast(d.action as varchar(64)) as action,
                      cast(d.summary as varchar(500)) as summary,
                      cast(null as varchar(64)) as from_status,
                      cast(null as varchar(64)) as to_status,
                      cast(null as varchar(64)) as reason_code,
                      cast(null as text) as payload_json,
                      cast(d.document_types as varchar(500)) as document_types
                    from loan_application_document_access_audit d
                    join loan_application la on la.id = d.loan_application_id
                    where (:__actorUsername is null or d.actor_username = :__actorUsername)
                      and (:__lspId is null or la.lsp_id = :__lspId)
                      and (:__loanApplicationId is null or d.loan_application_id = :__loanApplicationId)
                      and (:__borrowerId is null or la.borrower_id = :__borrowerId)
                      and (:__since is null or d.created_at >= :__since)
                      and (:__until is null or d.created_at <= :__until)
                    """);
        }

        if (streams.contains(AuditStream.PRODUCT)) {
            branches.add("""
                    select
                      cast('PRODUCT' as varchar(32)) as stream,
                      p.id as native_id,
                      p.created_at as occurred_at,
                      cast(p.actor_username as varchar(255)) as actor_username,
                      cast(p.correlation_id as varchar(128)) as correlation_id,
                      cast(null as uuid) as loan_application_id,
                      cast(null as uuid) as borrower_id,
                      cast(null as uuid) as lsp_id,
                      cast(p.loan_product_id as varchar(64)) as product_id,
                      cast(p.action as varchar(64)) as action,
                      cast(p.summary as varchar(500)) as summary,
                      cast(null as varchar(64)) as from_status,
                      cast(null as varchar(64)) as to_status,
                      cast(null as varchar(64)) as reason_code,
                      cast(null as text) as payload_json,
                      cast(null as varchar(500)) as document_types
                    from loan_product_audit_event p
                    where (:__actorUsername is null or p.actor_username = :__actorUsername)
                      and (:__productId is null or p.loan_product_id = :__productId)
                      and (:__since is null or p.created_at >= :__since)
                      and (:__until is null or p.created_at <= :__until)
                    """);
        }

        return String.join("\nunion all\n", branches);
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            throw new IllegalStateException("native_id must not be null");
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private static UUID toNullableUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    public record UnifiedAuditEventRow(
            String stream,
            UUID nativeId,
            Instant occurredAt,
            String actorUsername,
            String correlationId,
            UUID loanApplicationId,
            UUID borrowerId,
            UUID lspId,
            UUID productId,
            String action,
            String summary,
            String fromStatus,
            String toStatus,
            String reasonCode,
            String payloadJson,
            String documentTypes
    ) {
    }
}
