package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanForeclosureQuoteStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanForeclosureQuoteRepository extends JpaRepository<LoanForeclosureQuote, UUID> {

    List<LoanForeclosureQuote> findByLoanAccount_IdOrderByVersionDesc(UUID loanAccountId);

    Optional<LoanForeclosureQuote> findTopByLoanAccount_IdOrderByVersionDesc(UUID loanAccountId);

    List<LoanForeclosureQuote> findByStatusAndLoanAccount_IdIn(
            LoanForeclosureQuoteStatus status,
            Collection<UUID> loanAccountIds
    );
}
