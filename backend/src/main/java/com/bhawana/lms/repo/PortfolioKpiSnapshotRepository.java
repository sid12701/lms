package com.bhawana.lms.repo;

import com.bhawana.lms.domain.PortfolioKpiSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortfolioKpiSnapshotRepository extends JpaRepository<PortfolioKpiSnapshot, UUID> {

    Optional<PortfolioKpiSnapshot> findFirstByLspIsNullOrderByComputedAtDesc();

    Optional<PortfolioKpiSnapshot> findFirstByLsp_IdOrderByComputedAtDesc(UUID lspId);

    @Query("""
            select s from PortfolioKpiSnapshot s
            where s.computedAt = (
                select max(s2.computedAt) from PortfolioKpiSnapshot s2 where s2.lsp is not null
            )
            and s.lsp is not null
            order by s.lsp.name asc
            """)
    List<PortfolioKpiSnapshot> findLatestPerLsp();

    default Optional<PortfolioKpiSnapshot> findLatestGlobal() {
        return findFirstByLspIsNullOrderByComputedAtDesc();
    }

    /**
     * Bounded retention batch (L04): deletes at most {@code batchSize} rows older
     * than {@code cutoff}, oldest first — but never the most recent snapshot of a
     * scope (per-LSP or the global NULL-lsp row), so a scope whose worker has been
     * silent longer than the retention window still serves its latest reading.
     */
    @Modifying
    @Query(value = """
            DELETE FROM portfolio_kpi_snapshot
            WHERE id IN (
                SELECT id FROM portfolio_kpi_snapshot
                WHERE computed_at < :cutoff
                  AND id NOT IN (
                      SELECT DISTINCT ON (COALESCE(lsp_id, '00000000-0000-0000-0000-000000000000'::uuid)) id
                      FROM portfolio_kpi_snapshot
                      ORDER BY COALESCE(lsp_id, '00000000-0000-0000-0000-000000000000'::uuid),
                               computed_at DESC,
                               id
                  )
                ORDER BY computed_at ASC
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredBatchPreservingLatest(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
