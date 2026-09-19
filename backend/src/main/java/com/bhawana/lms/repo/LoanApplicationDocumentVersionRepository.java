package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationDocumentVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanApplicationDocumentVersionRepository extends JpaRepository<LoanApplicationDocumentVersion, UUID> {

    @Query("""
            select coalesce(max(version.versionNumber), 0)
            from LoanApplicationDocumentVersion version
            where version.loanApplicationId = :applicationId
              and version.documentType = :documentType
            """)
    int findMaxVersionNumber(
            @Param("applicationId") UUID applicationId,
            @Param("documentType") LoanApplicationDocumentType documentType
    );

    List<LoanApplicationDocumentVersion> findByLoanApplicationIdAndDocumentTypeOrderByVersionNumberAsc(
            UUID loanApplicationId,
            LoanApplicationDocumentType documentType
    );
}
