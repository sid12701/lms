package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Durable ownership/state record for one object the LMS wrote to document storage (M04).
 *
 * <p>State moves only through the native statements on
 * {@link com.bhawana.lms.repo.LoanDocumentObjectRepository}: PENDING is committed before the
 * object PUT, LINKED commits with the metadata that references the object, and
 * DELETING/DELETED belong to the orphan reconciler. Mapped read-only.</p>
 */
@Entity
@Immutable
@Table(name = "loan_document_object")
public class LoanDocumentObject {

    public static final String PENDING = "PENDING";
    public static final String LINKED = "LINKED";
    public static final String DELETING = "DELETING";
    public static final String DELETED = "DELETED";

    @Id
    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "loan_application_id", nullable = false)
    private UUID loanApplicationId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    protected LoanDocumentObject() {
    }

    public String getStorageKey() {
        return storageKey;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }

    public String getState() {
        return state;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }
}
