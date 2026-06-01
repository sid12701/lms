package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LspIpAllowlistEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LspIpAllowlistRepository extends JpaRepository<LspIpAllowlistEntry, UUID> {

    List<LspIpAllowlistEntry> findByLsp_Id(UUID lspId);

    boolean existsByLsp_IdAndCidr(UUID lspId, String cidr);

    long countByLsp_Id(UUID lspId);
}
