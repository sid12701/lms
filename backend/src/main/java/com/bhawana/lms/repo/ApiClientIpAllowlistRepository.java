package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ApiClientIpAllowlistEntry;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientIpAllowlistRepository extends JpaRepository<ApiClientIpAllowlistEntry, UUID> {

    List<ApiClientIpAllowlistEntry> findByApiClient_IdOrderByCidrAsc(UUID apiClientId);

    List<ApiClientIpAllowlistEntry> findByApiClient_IdInOrderByCidrAsc(Collection<UUID> apiClientIds);

    void deleteByApiClient_Id(UUID apiClientId);
}
