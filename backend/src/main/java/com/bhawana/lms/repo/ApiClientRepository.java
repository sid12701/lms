package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ApiClient;
import java.util.UUID;
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
}
