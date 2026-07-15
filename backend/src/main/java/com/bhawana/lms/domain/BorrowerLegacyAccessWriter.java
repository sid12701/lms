package com.bhawana.lms.domain;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Package-local bridge so {@link com.bhawana.lms.service.BorrowerLspRelationshipService}
 * can mutate the legacy access collection without exposing that API publicly on
 * {@link Borrower}.
 */
@Component
public class BorrowerLegacyAccessWriter {

    public void addVisibleLspId(Borrower borrower, UUID lspId) {
        Objects.requireNonNull(borrower, "borrower");
        borrower.addVisibleLspId(lspId);
    }
}
