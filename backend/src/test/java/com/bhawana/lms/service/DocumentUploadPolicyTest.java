package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
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
        byte[] sixMegabytes = new byte[6 * 1024 * 1024];
        System.arraycopy("%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, sixMegabytes, 0, 8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan.pdf",
                "application/pdf",
                sixMegabytes
        );

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
        fourMegabytes[0] = (byte) 0xFF;
        fourMegabytes[1] = (byte) 0xD8;
        fourMegabytes[2] = (byte) 0xFF;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan.jpg",
                "image/jpeg",
                fourMegabytes
        );

        assertDoesNotThrow(() -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.PAN_CARD, file));
    }

    @Test
    void panCardRejectsPlainTextDeclaredAsPdf() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "fake.pdf",
                "application/pdf",
                "not a pdf".getBytes()
        );

        BusinessRuleViolationException ex = assertThrows(
                BusinessRuleViolationException.class,
                () -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.PAN_CARD, file)
        );

        assertEquals("DOCUMENT_CONTENT_INVALID", ex.getErrorCode());
    }

    @Test
    void rejectsPathTraversalFileName() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../evil.pdf",
                "application/pdf",
                "%PDF-1.4".getBytes()
        );

        BusinessRuleViolationException ex = assertThrows(
                BusinessRuleViolationException.class,
                () -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.PAN_CARD, file)
        );

        assertEquals("DOCUMENT_FILE_NAME_INVALID", ex.getErrorCode());
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
        fourMegabytes[0] = (byte) 0xFF;
        fourMegabytes[1] = (byte) 0xD8;
        fourMegabytes[2] = (byte) 0xFF;
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
        nineMegabytes[0] = (byte) 0x89;
        nineMegabytes[1] = 0x50;
        nineMegabytes[2] = 0x4E;
        nineMegabytes[3] = 0x47;
        nineMegabytes[4] = 0x0D;
        nineMegabytes[5] = 0x0A;
        nineMegabytes[6] = 0x1A;
        nineMegabytes[7] = 0x0A;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "address.png",
                "image/png",
                nineMegabytes
        );

        assertDoesNotThrow(() -> DocumentUploadPolicy.validate(LoanApplicationDocumentType.ADDRESS_PROOF, file));
    }
}
