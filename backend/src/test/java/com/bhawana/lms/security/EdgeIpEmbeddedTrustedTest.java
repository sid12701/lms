package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bhawana.lms.common.web.ClientIpAddresses;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Embedded-server proof, explicit trusted proxy: with 127.0.0.1/32 (the test peer) and
 * 203.0.113.7/32 configured as trusted proxies, the single XFF line is evaluated with
 * right-to-left chain semantics — and every consumer still observes one shared IP.
 * Malformed or ambiguous trusted input is rejected with 400 and never attributed to the
 * proxy peer (which in production may itself be allowlisted).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = edgetest.EdgeEmbeddedApp.class,
        properties = {
                "server.forward-headers-strategy=NONE",
                "app.edge.trusted-proxies=127.0.0.1/32, 203.0.113.7/32",
                // Minimal context has no DataSource, so drop the 'db' member the main
                // application.yml declares in the readiness group.
                "management.endpoint.health.group.readiness.include=readinessState"
        })
class EdgeIpEmbeddedTrustedTest {

    @LocalServerPort
    private int port;

    private final RestTemplate http = lenientHttp();

    private static RestTemplate lenientHttp() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
            }
        });
        return template;
    }

    private ResponseEntity<Map> probe(HttpHeaders headers) {
        String url = "http://127.0.0.1:" + port + "/edge-probe";
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private void assertShared(String expected, ResponseEntity<Map> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, String> body = response.getBody();
        assertEquals(expected, body.get("canonical"));
        assertEquals(body.get("canonical"), body.get("rateIp"));
        assertEquals(body.get("canonical"), body.get("auditIp"));
    }

    @SuppressWarnings("unchecked")
    private void assertRejected(ResponseEntity<Map> response) {
        // Never 200-as-proxy: malformed/ambiguous trusted input is rejected pre-routing.
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals("INVALID_FORWARDED_HEADER", body.get("code"));
    }

    @Test
    void singleForwardedHopIsHonored() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "198.51.100.9");

        // 198.51.100.9 is not a trusted proxy, so the chain stops there: it is the client.
        assertShared("198.51.100.9", probe(headers));
    }

    @Test
    void multiHopChainWalksPastTrustedProxy() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "198.51.100.9, 203.0.113.7");

        // Right-to-left: peer trusted, 203.0.113.7 trusted, 198.51.100.9 is the client.
        assertShared("198.51.100.9", probe(headers));
    }

    @Test
    void spoofedLeftmostHopCannotOverride() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "198.51.100.1, 198.51.100.9, 203.0.113.7");

        // Attacker prepends an address; the rightmost untrusted hop still wins.
        assertShared("198.51.100.9", probe(headers));
    }

    @Test
    void ipv6ClientIsCanonicalized() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "2001:db8::1");

        assertShared(ClientIpAddresses.canonicalize("2001:db8::1"), probe(headers));
    }

    @Test
    void nonXffHeadersAreNeverEvaluated() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "198.51.100.9");
        headers.add("X-Real-IP", "203.0.113.99");
        headers.add("Forwarded", "for=203.0.113.99;proto=https");

        // Single-XFF contract: only the XFF line is evaluated, even from a trusted peer.
        assertShared("198.51.100.9", probe(headers));
    }

    @Test
    void missingHeaderFromTrustedPeerIsRejected() {
        // The edge always sets exactly one line; a trusted peer with no XFF must not be
        // attributed as itself (it may be allowlisted).
        assertRejected(probe(new HttpHeaders()));
    }

    @Test
    void malformedTrustedChainIsRejectedNotAttributedToProxy() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "2001:db8::1, not-an-ip!!!");

        assertRejected(probe(headers));
    }

    @Test
    void overlongTrustedChainIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For",
                "198.51.100.1, 198.51.100.2, 198.51.100.3, 198.51.100.4,"
                        + " 198.51.100.5, 198.51.100.6, 198.51.100.7, 198.51.100.8,"
                        + " 198.51.100.9");

        assertRejected(probe(headers));
    }

    @Test
    void duplicateHeaderLinesAreRejectedOverActualHttp() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "198.51.100.9");
        headers.add("X-Forwarded-For", "203.0.113.7");

        // Two XFF field lines are ambiguous as a set (first may be attacker-controlled
        // while the edge appended the client to the second, or vice versa): reject.
        assertRejected(probe(headers));
    }
}
