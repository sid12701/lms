package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LspApiIdempotencyRecordRepository extends JpaRepository<LspApiIdempotencyRecord, UUID> {

    Optional<LspApiIdempotencyRecord> findByLspIdAndOperationKeyAndIdempotencyKey(
            UUID lspId,
            String operationKey,
            String idempotencyKey
    );
}
