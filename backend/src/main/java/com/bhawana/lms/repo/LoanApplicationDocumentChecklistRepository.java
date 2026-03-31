package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationDocumentChecklistRepository extends JpaRepository<LoanApplicationDocumentChecklist, UUID> {

    List<LoanApplicationDocumentChecklist> findByLoanApplication_IdOrderByCreatedAtAsc(UUID loanApplicationId);

    Optional<LoanApplicationDocumentChecklist> findByLoanApplication_IdAndDocumentType(
            UUID loanApplicationId,
            LoanApplicationDocumentType documentType
    );
}
