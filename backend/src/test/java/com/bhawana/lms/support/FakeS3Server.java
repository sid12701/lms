package com.bhawana.lms.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal in-process S3 endpoint for storage-adapter tests (M05). Path-style requests only:
 * {@code PUT/GET/DELETE /{bucket}/{key}} and {@code GET /{bucket}?list-type=2}. Keys under the
 * {@code slow/} prefix hang for {@code slowDelay} so deadline tests can simulate a stalled
 * endpoint without a real outage.
 *
 * <p>Deliberately ignores SigV4 authentication — it exercises the SDK's HTTP/timeout/streaming
 * path, not signing.
 */
public final class FakeS3Server implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile Duration slowDelay = Duration.ofSeconds(3);

    private FakeS3Server(HttpServer server) {
        this.server = server;
    }

    public static FakeS3Server start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            FakeS3Server fake = new FakeS3Server(server);
            server.createContext("/", fake::handle);
            // A pool so concurrent deadline/concurrency tests are genuinely parallel.
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return fake;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setSlowDelay(Duration slowDelay) {
        this.slowDelay = slowDelay;
    }

    public int requestCount() {
        return requestCount.get();
    }

    public Map<String, byte[]> objects() {
        return objects;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String path = exchange.getRequestURI().getPath() == null ? "/" : exchange.getRequestURI().getPath();
        // /{bucket}/{key...} — key may be empty for bucket-level requests (list).
        int secondSlash = path.indexOf('/', 1);
        String key = secondSlash < 0 ? "" : path.substring(secondSlash + 1);

        if (key.startsWith("slow")) {
            try {
                Thread.sleep(slowDelay.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                objects.put(key, readRequestBody(exchange));
                exchange.getResponseHeaders().set("ETag", "\"fake-etag\"");
                exchange.sendResponseHeaders(200, -1);
            }
            case "GET" -> {
                if (query.contains("list-type=2")) {
                    writeBytes(exchange, 200, listXml());
                } else if (objects.containsKey(key)) {
                    writeBytes(exchange, 200, objects.get(key));
                } else {
                    writeBytes(exchange, 404, ("<Error><Code>NoSuchKey</Code>"
                            + "<Message>no such key</Message></Error>").getBytes(StandardCharsets.UTF_8));
                }
            }
            case "DELETE" -> {
                objects.remove(key);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> writeBytes(exchange, 400, "<Error><Code>BadRequest</Code></Error>".getBytes(StandardCharsets.UTF_8));
        }
        exchange.close();
    }

    private byte[] listXml() {
        StringBuilder xml = new StringBuilder(
                "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                        + "<Name>test-bucket</Name><KeyCount>" + objects.size() + "</KeyCount>"
                        + "<MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>");
        for (Map.Entry<String, byte[]> entry : objects.entrySet()) {
            xml.append("<Contents><Key>").append(entry.getKey()).append("</Key>")
                    .append("<Size>").append(entry.getValue().length).append("</Size></Contents>");
        }
        xml.append("</ListBucketResult>");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /**
     * The AWS SDK sends streamed PUT bodies as {@code aws-chunked} (chunk framing with
     * per-chunk signatures and trailing checksum headers). Unwrap the framing so tests see
     * the object bytes, not the wire encoding.
     */
    private static byte[] readRequestBody(HttpExchange exchange) throws IOException {
        String transferEncoding = header(exchange, "Transfer-encoding");
        String contentEncoding = header(exchange, "Content-encoding");
        if ((transferEncoding != null && transferEncoding.toLowerCase().contains("chunked"))
                || (contentEncoding != null && contentEncoding.toLowerCase().contains("aws-chunked"))) {
            return decodeChunked(exchange.getRequestBody());
        }
        return exchange.getRequestBody().readAllBytes();
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    private static byte[] decodeChunked(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (true) {
            String headerLine = readLine(in);
            if (headerLine == null) {
                break;
            }
            int extension = headerLine.indexOf(';');
            String sizeToken = (extension < 0 ? headerLine : headerLine.substring(0, extension)).trim();
            if (sizeToken.isEmpty()) {
                continue;
            }
            int size = Integer.parseInt(sizeToken, 16);
            if (size == 0) {
                // Terminal chunk: consume trailer lines through the blank line.
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // discard
                }
                break;
            }
            out.write(in.readNBytes(size));
            readLine(in); // CRLF after the chunk data
        }
        return out.toByteArray();
    }

    private static String readLine(java.io.InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (current == '\n' && previous == '\r') {
                line.setLength(line.length() - 1);
                return line.toString();
            }
            line.append((char) current);
            previous = current;
        }
        return line.isEmpty() ? null : line.toString();
    }
}
