package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentType;

/** One document write to record against an application's checklist. */
public record DocumentSubmission(
        LoanApplicationDocumentType documentType,
        String note,
        String fileName,
        String fileReference,
        String sourceReference,
        String contentType,
        Long fileSizeBytes,
        String fileChecksum,
        String storageKey,
        boolean lmsManagedContent
) {

    /** Legacy metadata-only submission: a reference to a document the LMS does not hold. */
    public static DocumentSubmission metadata(
            LoanApplicationDocumentType documentType,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType
    ) {
        return new DocumentSubmission(
                documentType, note, fileName, fileReference, sourceReference, contentType,
                null, null, null, false
        );
    }

    /** A document whose bytes the LMS holds at {@code storageKey}. */
    public static DocumentSubmission stored(
            LoanApplicationDocumentType documentType,
            String note,
            String sourceReference,
            String fileName,
            String fileReference,
            String contentType,
            long fileSizeBytes,
            String fileChecksum,
            String storageKey
    ) {
        return new DocumentSubmission(
                documentType, note, fileName, fileReference, sourceReference, contentType,
                fileSizeBytes, fileChecksum, storageKey, true
        );
    }
}
