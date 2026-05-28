package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.support.MinioTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class R2ReportStorageServiceIntegrationTest extends MinioTestSupport {

    @Autowired
    private ReportStorageService reportStorageService;

    @Test
    void storedReportBytesRoundTripExactly() {
        byte[] payload = "loanId,principal\n11111,12345.67\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ReportStorageService.ReportStorageDescriptor descriptor = new ReportStorageService.ReportStorageDescriptor(
                UUID.randomUUID(),
                ReportType.PORTFOLIO_MIS,
                "portfolio-mis-2026-05-28.csv",
                "text/csv"
        );

        ReportStorageService.StoredReport stored = reportStorageService.store(descriptor, payload);
        byte[] retrieved = reportStorageService.retrieve(stored.storageKey());

        assertThat(stored.storageKey()).isNotBlank();
        assertThat(stored.fileName()).isEqualTo("portfolio-mis-2026-05-28.csv");
        assertThat(stored.mediaType()).isEqualTo("text/csv");
        assertThat(stored.sizeBytes()).isEqualTo(payload.length);
        assertThat(retrieved).isEqualTo(payload);
    }

    @Test
    void retrieveUnknownKeyThrows() {
        assertThatThrownBy(() -> reportStorageService.retrieve("reports/does-not-exist/missing.csv"))
                .isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
    }

    @Test
    void binaryPayloadSurvivesRoundTrip() {
        // Bytes that would be mangled by any UTF-8 String round-trip:
        // null bytes, lone high-surrogate continuations, BOM, and a
        // raw PDF magic header.
        byte[] payload = new byte[]{
                0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, // %PDF-1.7
                0x00, 0x01, 0x02, 0x03,
                (byte) 0xFF, (byte) 0xFE,                       // UTF-16 BOM (invalid as UTF-8)
                (byte) 0xC0, (byte) 0xAF,                       // overlong slash (invalid UTF-8)
                (byte) 0xED, (byte) 0xA0, (byte) 0x80,          // lone high surrogate (invalid UTF-8)
                0x0A,
                (byte) 0x80, (byte) 0x81, (byte) 0x82           // continuation bytes without a leader
        };
        ReportStorageService.ReportStorageDescriptor descriptor = new ReportStorageService.ReportStorageDescriptor(
                java.util.UUID.randomUUID(),
                com.bhawana.lms.domain.ReportType.PORTFOLIO_MIS,
                "binary-fixture.pdf",
                "application/pdf"
        );

        ReportStorageService.StoredReport stored = reportStorageService.store(descriptor, payload);
        byte[] retrieved = reportStorageService.retrieve(stored.storageKey());

        assertThat(retrieved).containsExactly(payload);
        assertThat(stored.sizeBytes()).isEqualTo(payload.length);
    }
}
