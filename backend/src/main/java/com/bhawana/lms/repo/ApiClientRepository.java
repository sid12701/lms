package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ApiClient;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientRepository extends JpaRepository<ApiClient, UUID> {

    /**
     * F-11: raw equality on client_id. Values are system-generated lowercase
     * UUID hex (ApiClientManagementService#generateClientId), so the unique
     * index on client_id satisfies the lookup. The previous IgnoreCase variants
     * forced sequential scans.
     */
    boolean existsByClientId(String clientId);

    java.util.Optional<ApiClient> findByClientId(String clientId);

    java.util.List<ApiClient> findByLsp_Id(UUID lspId);

    /**
     * Admin listing joins the owning LSP in the same statement.
     *
     * {@code ApiClient.lsp} is a EAGER {@code @ManyToOne}, which guarantees the
     * association is loaded but not that it is loaded efficiently: without this
     * graph Hibernate issues the root select and then one extra select per row,
     * so the listing cost grows with the number of clients. The graph collapses
     * that into a single join.
     */
    @Override
    @EntityGraph(attributePaths = "lsp")
    java.util.List<ApiClient> findAll();

    /**
     * Machine-principal row lock (no outer-join FOR UPDATE). Human family logic
     * never touches this branch; machine refresh keeps its own lock-first path.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from ApiClient c where c.id = :id")
    java.util.Optional<ApiClient> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
