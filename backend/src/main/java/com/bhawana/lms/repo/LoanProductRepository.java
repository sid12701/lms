package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanProduct;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanProductRepository extends JpaRepository<LoanProduct, UUID> {

    boolean existsByCodeIgnoreCase(String code);

    java.util.Optional<LoanProduct> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
