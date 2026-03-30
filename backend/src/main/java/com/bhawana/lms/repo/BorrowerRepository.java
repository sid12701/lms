package com.bhawana.lms.repo;

import com.bhawana.lms.domain.Borrower;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BorrowerRepository extends JpaRepository<Borrower, UUID> {

    Optional<Borrower> findByPanIgnoreCase(String pan);
}
