package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanDocumentObject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * State machine for {@code loan_document_object} (M04). Every transition is a single
 * conditional statement so concurrent uploads and reconciler instances serialize on the row.
 */
public interface LoanDocumentObjectRepository extends JpaRepository<LoanDocumentObject, String> {

    /**
     * Record (or re-arm) ownership of an object before it is written. A LINKED object is left
     * as is — its bytes are already stored and referenced. A DELETED one is re-armed to
     * PENDING. A DELETING one is left DELETING so the caller can back off. Returns the state
     * after the statement.
     */
    @Query(value = """
            insert into loan_document_object (
                storage_key, loan_application_id, document_type, file_checksum, file_size_bytes,
                state, last_attempt_at, created_at
            ) values (
                :storageKey, :applicationId, :documentType, :checksum, :sizeBytes,
                'PENDING', now(), now()
            )
            on conflict (storage_key) do update set
                state = case when loan_document_object.state = 'DELETED' then 'PENDING'
                             else loan_document_object.state end,
                last_attempt_at = case when loan_document_object.state in ('PENDING', 'DELETED') then now()
                                       else loan_document_object.last_attempt_at end,
                deleted_at = case when loan_document_object.state = 'DELETED' then null
                                  else loan_document_object.deleted_at end
            returning state
            """, nativeQuery = true)
    String upsertPending(
            @Param("storageKey") String storageKey,
            @Param("applicationId") UUID applicationId,
            @Param("documentType") String documentType,
            @Param("checksum") String checksum,
            @Param("sizeBytes") long sizeBytes
    );

    /** Link an object to committed metadata. Returns 0 when the reconciler already took it. */
    @Modifying
    @Query(value = """
            update loan_document_object
            set state = 'LINKED', linked_at = coalesce(linked_at, now())
            where storage_key = :storageKey
              and state in ('PENDING', 'LINKED')
            """, nativeQuery = true)
    int markLinked(@Param("storageKey") String storageKey);

    @Query(value = """
            select storage_key
            from loan_document_object
            where (state = 'PENDING' and last_attempt_at < :cutoff)
               or state = 'DELETING'
            order by last_attempt_at
            limit :limit
            """, nativeQuery = true)
    List<String> findReconcileCandidates(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * Claim an expired PENDING object for deletion only if no document metadata references its
     * key — rechecked in the same statement that changes the state.
     */
    @Modifying
    @Query(value = """
            update loan_document_object object
            set state = 'DELETING', last_attempt_at = now()
            where object.storage_key = :storageKey
              and object.state = 'PENDING'
              and object.last_attempt_at < :cutoff
              and not exists (
                  select 1 from loan_application_document_version version
                  where version.storage_key = object.storage_key)
              and not exists (
                  select 1 from loan_application_document_checklist checklist
                  where checklist.storage_key = object.storage_key)
            """, nativeQuery = true)
    int claimForDeletion(@Param("storageKey") String storageKey, @Param("cutoff") Instant cutoff);

    /** Repair a PENDING row whose key is in fact referenced (never delete referenced bytes). */
    @Modifying
    @Query(value = """
            update loan_document_object object
            set state = 'LINKED', linked_at = coalesce(object.linked_at, now())
            where object.storage_key = :storageKey
              and object.state = 'PENDING'
              and (exists (
                      select 1 from loan_application_document_version version
                      where version.storage_key = object.storage_key)
                   or exists (
                      select 1 from loan_application_document_checklist checklist
                      where checklist.storage_key = object.storage_key))
            """, nativeQuery = true)
    int relinkIfReferenced(@Param("storageKey") String storageKey);

    @Query(value = "select state from loan_document_object where storage_key = :storageKey", nativeQuery = true)
    String findState(@Param("storageKey") String storageKey);

    @Modifying
    @Query(value = """
            update loan_document_object
            set state = 'DELETED', deleted_at = now()
            where storage_key = :storageKey
              and state = 'DELETING'
            """, nativeQuery = true)
    int markDeleted(@Param("storageKey") String storageKey);

    List<LoanDocumentObject> findByLoanApplicationId(UUID loanApplicationId);
}
