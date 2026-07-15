package com.bhawana.lms.repo;

import com.bhawana.lms.domain.BorrowerLspRelationship;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BorrowerLspRelationshipRepository extends JpaRepository<BorrowerLspRelationship, UUID> {

    Optional<BorrowerLspRelationship> findByBorrower_IdAndLsp_Id(UUID borrowerId, UUID lspId);

    List<BorrowerLspRelationship> findByBorrower_IdOrderByFirstSourcedAtAsc(UUID borrowerId);

    List<BorrowerLspRelationship> findByBorrower_IdIn(Collection<UUID> borrowerIds);

    long countByBorrower_Id(UUID borrowerId);
}
