package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.support.FakeS3Server;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * M05: the R2 report adapter shares one client with bounded call/attempt deadlines, streams
 * downloads without heap-buffering, and closes the client once at shutdown.
 */
class R2ReportStorageServiceTest {

    private FakeS3Server server;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private ReportStorageProperties props(String endpoint) {
        ReportStorageProperties properties = new ReportStorageProperties();
        ReportStorageProperties.R2 r2 = properties.getR2();
        r2.setEndpoint(endpoint);
        r2.setAccessKey("test-access");
        r2.setSecretKey("test-secret");
        r2.setBucket("reports-bucket");
        return properties;
    }

    private ReportStorageService.ReportStorageDescriptor descriptor(String fileName) {
        return new ReportStorageService.ReportStorageDescriptor(
                UUID.randomUUID(), ReportType.PORTFOLIO_MIS, fileName, "text/csv");
    }

    @Test
    void storeFromFileThenStreamDownloadSharesOneClient() throws Exception {
        server = FakeS3Server.start();
        R2ReportStorageService service = new R2ReportStorageService(props(server.endpoint()));

        byte[] csv = "a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("report.csv"), csv);

        ReportStorageService.StoredReport stored = service.store(descriptor("portfolio.csv"), file);
        assertThat(stored.sizeBytes()).isEqualTo(csv.length);
        S3Client sharedClient = service.peekClient();
        assertThat(sharedClient).isNotNull();

        // The M05 addition: a download path that streams rather than buffering the export.
        try (ReportStorageService.ReportStream stream = service.openStream(stored.storageKey())) {
            assertThat(stream.contentLength()).isEqualTo(csv.length);
            assertThat(stream.content().readAllBytes()).isEqualTo(csv);
        }

        assertThat(service.retrieve(stored.storageKey())).isEqualTo(csv);
        assertThat(service.peekClient()).isSameAs(sharedClient);
        service.shutdown();
    }

    @Test
    void openStreamOnMissingKeyIsANotFoundBeforeAnyBytesAreWritten() throws Exception {
        server = FakeS3Server.start();
        R2ReportStorageService service = new R2ReportStorageService(props(server.endpoint()));

        assertThatThrownBy(() -> service.openStream("reports/none/missing.csv"))
                .isInstanceOf(ResourceNotFoundException.class);
        service.shutdown();
    }

    @Test
    void stalledEndpointFailsInsideTheConfiguredDeadline() throws Exception {
        server = FakeS3Server.start();
        server.setSlowDelay(Duration.ofSeconds(10));
        ReportStorageProperties properties = props(server.endpoint());
        ReportStorageProperties.R2 r2 = properties.getR2();
        r2.setApiCallAttemptTimeout(Duration.ofMillis(400));
        r2.setApiCallTimeout(Duration.ofSeconds(2));
        r2.setConnectionTimeout(Duration.ofMillis(500));

        R2ReportStorageService service = new R2ReportStorageService(properties);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.openStream("slow/report.csv"))
                .isInstanceOf(software.amazon.awssdk.core.exception.SdkException.class);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMs).isLessThan(4_000);
        service.shutdown();
    }

    @Test
    void closingAResponseStreamDoesNotCloseTheSharedClient() throws Exception {
        S3Client client = mock(S3Client.class);
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two".getBytes(StandardCharsets.UTF_8);
        R2ReportStorageService service =
                new R2ReportStorageService(new ReportStorageProperties(), client);
        try (ResponseInputStream<GetObjectResponse> firstStream = new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength((long) first.length).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(first)));
                ResponseInputStream<GetObjectResponse> secondStream = new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength((long) second.length).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(second)))) {
            when(client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(firstStream, secondStream);

            var streamA = service.openStream("reports/x/a.csv");
            var streamB = service.openStream("reports/x/b.csv");
            streamA.close();
            assertThat(streamB.content().readAllBytes()).isEqualTo(second);
            streamB.close();
            verify(client, never()).close();
        }

        service.shutdown();
        service.shutdown();
        verify(client, times(1)).close();
    }

    @Test
    void unconfiguredR2FailsCleanlyWithoutBuildingAClient() {
        R2ReportStorageService service = new R2ReportStorageService(new ReportStorageProperties());

        assertThatThrownBy(() -> service.retrieve("reports/x/a.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThat(service.peekClient()).isNull();
        service.shutdown();
    }
}
