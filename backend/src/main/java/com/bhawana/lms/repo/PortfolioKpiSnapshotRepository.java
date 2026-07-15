package com.bhawana.lms.repo;

import com.bhawana.lms.domain.PortfolioKpiSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
