package com.bhawana.lms.repo;

import com.bhawana.lms.domain.Borrower;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BorrowerRepository extends JpaRepository<Borrower, UUID> {

    Optional<Borrower> findByPanIgnoreCase(String pan);

    List<Borrower> findTop10ByMobileOrderByUpdatedAtDesc(String mobile);

    /**
     * Case-insensitive substring search across the borrower identity columns
     * exposed on the admin borrowers list page (name, PAN, mobile, email).
     * `q` is null or blank to return every borrower.
     */
    @Query("""
            SELECT b FROM Borrower b
            WHERE :q IS NULL
               OR LENGTH(TRIM(:q)) = 0
               OR LOWER(b.fullName) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(b.pan)      LIKE LOWER(CONCAT('%', :q, '%'))
               OR b.mobile          LIKE CONCAT('%', :q, '%')
               OR LOWER(b.email)    LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Borrower> searchBorrowers(@Param("q") String q, Pageable pageable);
}
