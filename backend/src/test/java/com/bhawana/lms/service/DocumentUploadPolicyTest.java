package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentUploadPolicyTest {

    @Test
    void loanAgreementRejectsNonPdfMime() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "agreement.jpg",
                "image/jpeg",
                "jpeg-body".getBytes()
        );

        BusinessRuleViolationException ex = assertThrows(
                BusinessRuleViolationException.class,
                () -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.LOAN_AGREEMENT, file)
        );

        assertEquals("DOCUMENT_MIME_NOT_ALLOWED", ex.getErrorCode());
        assertEquals("LOAN_AGREEMENT", ex.getFieldErrors().get("documentType"));
    }

    @Test
    void loanAgreementAcceptsPdfWithinGlobalMax() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "agreement.pdf",
                "application/pdf",
                "%PDF-1.4".getBytes()
        );

        assertDoesNotThrow(() -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.LOAN_AGREEMENT, file));
    }

    @Test
    void panCardRejectsPngMime() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan.png",
                "image/png",
                new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 }
        );

        BusinessRuleViolationException ex = assertThrows(
                BusinessRuleViolationException.class,
                () -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.PAN_CARD, file)
        );

        assertEquals("DOCUMENT_MIME_NOT_ALLOWED", ex.getErrorCode());
        assertEquals("PAN_CARD", ex.getFieldErrors().get("documentType"));
    }

    @Test
    void panCardRejectsDeclaredSizeAboveFiveMegabytes() {
        long sixMegabytes = 6L * 1024L * 1024L;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan.pdf",
                "application/pdf",
                new byte[0]
        ) {
            @Override
            public long getSize() {
                return sixMegabytes;
            }
        };

        BusinessRuleViolationException ex = assertThrows(
                BusinessRuleViolationException.class,
                () -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.PAN_CARD, file)
        );

        assertEquals("DOCUMENT_FILE_TOO_LARGE", ex.getErrorCode());
        assertEquals("PAN_CARD", ex.getFieldErrors().get("documentType"));
        assertEquals(String.valueOf(DocumentUploadPolicy.IDENTITY_DOCUMENT_MAX_BYTES), ex.getFieldErrors().get("maxFileSizeBytes"));
    }

    @Test
    void panCardAcceptsFourMegabyteJpeg() {
        byte[] fourMegabytes = new byte[4 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan.jpg",
                "image/jpeg",
                fourMegabytes
        );

        assertDoesNotThrow(() -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.PAN_CARD, file));
    }

    @Test
    void aadhaarFileUsesSameIdentityDocumentConstraintsAsPan() {
        MockMultipartFile png = new MockMultipartFile(
                "file",
                "aadhaar.png",
                "image/png",
                "png".getBytes()
        );
        assertThrows(
                BusinessRuleViolationException.class,
                () -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.AADHAAR_FILE, png)
        );

        byte[] fourMegabytes = new byte[4 * 1024 * 1024];
        MockMultipartFile jpeg = new MockMultipartFile(
                "file",
                "aadhaar.jpg",
                "image/jpeg",
                fourMegabytes
        );
        assertDoesNotThrow(() -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.AADHAAR_FILE, jpeg));
    }

    @Test
    void addressProofKeepsGlobalTenMegabyteCapAndPngMime() {
        byte[] nineMegabytes = new byte[9 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "address.png",
                "image/png",
                nineMegabytes
        );

        assertDoesNotThrow(() -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.ADDRESS_PROOF, file));
    }
}
