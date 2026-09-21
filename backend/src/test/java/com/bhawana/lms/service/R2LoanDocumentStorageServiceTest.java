package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.DocumentStorageMisconfiguredException;
import com.bhawana.lms.common.api.error.DocumentStorageUnavailableException;
import com.bhawana.lms.support.FakeS3Server;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * M05: the R2 document adapter uses ONE lazily-built client with explicit call/attempt
 * deadlines — concurrent calls reuse it, closing a returned stream releases only the pooled
 * connection, shutdown closes the client once, and a slow or dead endpoint fails inside the
 * configured deadline instead of hanging a request thread.
 */
class R2LoanDocumentStorageServiceTest {

    private FakeS3Server server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private DocumentStorageProperties props(String endpoint) {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        DocumentStorageProperties.R2 r2 = properties.getR2();
        r2.setEndpoint(endpoint);
        r2.setAccessKey("test-access");
        r2.setSecretKey("test-secret");
        r2.setBucket("test-bucket");
        return properties;
    }

    private DocumentStorageDescriptor descriptor(String key) {
        return new DocumentStorageDescriptor("doc.pdf", "application/pdf", "checksum-" + key, key);
    }

    @Test
    void storeRetrieveOpenStreamAndDeleteRoundTripThroughSharedClient() throws Exception {
        server = FakeS3Server.start();
        DocumentStorageProperties properties = props(server.endpoint());
        R2LoanDocumentStorageService service = new R2LoanDocumentStorageService(properties);

        byte[] content = "document-body".getBytes(StandardCharsets.UTF_8);
        service.store(descriptor("loan/app-1/pan/doc.pdf"), content);

        assertThat(service.peekClient()).isNotNull();
        assertThat(service.retrieve("loan/app-1/pan/doc.pdf")).isEqualTo(content);

        LoanDocumentStorageService.RetrievedDocumentStream stream =
                service.openStream("loan/app-1/pan/doc.pdf");
        assertThat(stream.contentLength()).isEqualTo(content.length);
        try (var content1 = stream.content()) {
            assertThat(content1.readAllBytes()).isEqualTo(content);
        }

        service.delete("loan/app-1/pan/doc.pdf");
        assertThatThrownBy(() -> service.retrieve("loan/app-1/pan/doc.pdf"))
                .isInstanceOf(DocumentNotFoundException.class);
        service.shutdown();
    }

    @Test
    void concurrentCallsReuseOneClientAndClosingOneStreamKeepsOthersAlive() throws Exception {
        server = FakeS3Server.start();
        DocumentStorageProperties properties = props(server.endpoint());
        R2LoanDocumentStorageService service = new R2LoanDocumentStorageService(properties);

        // Prime the lazy build so the identity assertion below is meaningful.
        service.store(descriptor("loan/app/warmup.bin"), new byte[]{7});
        S3Client sharedClient = service.peekClient();
        assertThat(sharedClient).isNotNull();

        int threads = 8;
        int opsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < opsPerThread; i++) {
                    String key = "loan/app/concurrent-" + threadIndex + "-" + i + ".bin";
                    byte[] payload = ("payload-" + threadIndex + "-" + i).getBytes(StandardCharsets.UTF_8);
                    service.store(descriptor(key), payload);
                    try (var streamContent = service.openStream(key).content()) {
                        assertThat(streamContent.readAllBytes()).isEqualTo(payload);
                    }
                    service.delete(key);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // One client object across all 240 operations — the lazy build ran once.
        assertThat(service.peekClient()).isSameAs(sharedClient);
        service.shutdown();
    }

    @Test
    void closingAResponseStreamDoesNotCloseTheSharedClient() throws Exception {
        // The pre-M05 code wrapped each stream in a per-call client it closed on stream close;
        // with the shared client, closing a stream must only release its pooled connection.
        S3Client client = mock(S3Client.class);
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        R2LoanDocumentStorageService service =
                new R2LoanDocumentStorageService(new DocumentStorageProperties(), client);
        try (ResponseInputStream<GetObjectResponse> firstStream = new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength((long) first.length).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(first)));
                ResponseInputStream<GetObjectResponse> secondStream = new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength((long) second.length).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(second)))) {
            when(client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(firstStream, secondStream);

            var streamA = service.openStream("loan/a/one.bin");
            var streamB = service.openStream("loan/a/two.bin");
            streamA.content().close();

            // Closing stream A must not kill the shared client — stream B still reads to the end.
            assertThat(streamB.content().readAllBytes()).isEqualTo(second);
            streamB.content().close();
            verify(client, never()).close();
        }

        // Shutdown closes the shared client exactly once, even if invoked twice.
        service.shutdown();
        service.shutdown();
        verify(client, times(1)).close();
    }

    @Test
    void storeDeleteAndRetrieveAllUseTheInjectedSharedClient() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        R2LoanDocumentStorageService service =
                new R2LoanDocumentStorageService(new DocumentStorageProperties(), client);
        service.store(descriptor("loan/a/doc.bin"), new byte[]{1, 2, 3});
        service.delete("loan/a/doc.bin");

        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(client).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
        verify(client, never()).close();
        service.shutdown();
        verify(client, times(1)).close();
    }

    @Test
    void stalledEndpointFailsInsideTheConfiguredDeadline() throws Exception {
        server = FakeS3Server.start();
        server.setSlowDelay(Duration.ofSeconds(10));
        DocumentStorageProperties properties = props(server.endpoint());
        DocumentStorageProperties.R2 r2 = properties.getR2();
        r2.setApiCallAttemptTimeout(Duration.ofMillis(400));
        r2.setApiCallTimeout(Duration.ofSeconds(2));
        r2.setConnectionTimeout(Duration.ofMillis(500));

        R2LoanDocumentStorageService service = new R2LoanDocumentStorageService(properties);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.openStream("slow/doc.bin"))
                .isInstanceOf(DocumentStorageUnavailableException.class);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMs)
                .as("a stalled endpoint must fail inside the API-call deadline, not hang")
                .isLessThan(4_000);

        // The failed openStream must not have handed out a stream, and the shared client must
        // still serve subsequent calls — the same object model holds for store.
        assertThatThrownBy(() -> service.store(descriptor("slow/upload.bin"), new byte[]{1}))
                .isInstanceOf(DocumentStorageUnavailableException.class);
        service.shutdown();
    }

    @Test
    void unreachableEndpointFailsFastInsideTheDeadline() {
        // Nothing listens on 127.0.0.1:1 — TCP connect is refused immediately.
        DocumentStorageProperties properties = props("http://127.0.0.1:1");
        properties.getR2().setConnectionTimeout(Duration.ofSeconds(2));
        R2LoanDocumentStorageService service = new R2LoanDocumentStorageService(properties);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.retrieve("loan/app/doc.bin"))
                .isInstanceOf(DocumentStorageUnavailableException.class);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMs).isLessThan(5_000);
        service.shutdown();
    }

    @Test
    void unconfiguredR2FailsCleanlyWithoutBuildingAClient() {
        R2LoanDocumentStorageService service = new R2LoanDocumentStorageService(new DocumentStorageProperties());

        assertThatThrownBy(() -> service.retrieve("loan/app/doc.bin"))
                .isInstanceOf(DocumentStorageMisconfiguredException.class);
        assertThat(service.peekClient()).isNull();
        service.shutdown();
    }

    @Test
    void callsAfterShutdownFailInsteadOfRebuildingAClient() {
        S3Client client = mock(S3Client.class);
        R2LoanDocumentStorageService service =
                new R2LoanDocumentStorageService(new DocumentStorageProperties(), client);
        service.shutdown();

        assertThatThrownBy(() -> service.retrieve("loan/app/doc.bin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shut down");
        verify(client, times(1)).close();
    }
}
