package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanForeclosureQuoteStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanForeclosureQuoteRepository extends JpaRepository<LoanForeclosureQuote, UUID> {

    List<LoanForeclosureQuote> findByLoanAccount_IdOrderByVersionDesc(UUID loanAccountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quote from LoanForeclosureQuote quote where quote.id = :id")
    Optional<LoanForeclosureQuote> findByIdForUpdate(@Param("id") UUID id);

    Optional<LoanForeclosureQuote> findTopByLoanAccount_IdOrderByVersionDesc(UUID loanAccountId);

    List<LoanForeclosureQuote> findByStatusAndLoanAccount_IdIn(
            LoanForeclosureQuoteStatus status,
            Collection<UUID> loanAccountIds
    );
}
