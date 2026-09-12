package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Embedded-server proof, untrusted peer: over a real socket (peer 127.0.0.1, no trusted
 * proxies configured) every forged forwarding header must be ignored — the canonical IP seen
 * by auth, allowlist, rate limiting, and audit is the socket peer.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = edgetest.EdgeEmbeddedApp.class,
        properties = {
                "server.forward-headers-strategy=NONE",
                // Minimal context has no DataSource, so drop the 'db' member the main
                // application.yml declares in the readiness group.
                "management.endpoint.health.group.readiness.include=readinessState"
        })
class EdgeIpEmbeddedUntrustedTest {

    @LocalServerPort
    private int port;

    private final RestTemplate http = new RestTemplate();

    private Map<String, String> probe(HttpHeaders headers) {
        String url = "http://127.0.0.1:" + port + "/edge-probe";
        ResponseEntity<Map> response =
                http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> body = response.getBody();
        return body;
    }

    @Test
    void directPeerWithNoHeadersYieldsPeerEverywhere() {
        Map<String, String> body = probe(new HttpHeaders());

        assertEquals("127.0.0.1", body.get("peer"));
        assertEquals("127.0.0.1", body.get("canonical"));
        assertEquals(body.get("canonical"), body.get("rateIp"));
        assertEquals(body.get("canonical"), body.get("auditIp"));
    }

    @Test
    void forgedXForwardedForIsIgnored() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "203.0.113.7, 10.0.0.5");

        Map<String, String> body = probe(headers);

        // The socket peer is preserved (container does not rewrite it) and the spoof is ignored.
        assertEquals("127.0.0.1", body.get("peer"));
        assertEquals("127.0.0.1", body.get("canonical"));
        assertEquals(body.get("canonical"), body.get("rateIp"));
        assertEquals(body.get("canonical"), body.get("auditIp"));
    }

    @Test
    void forgedXRealIpAndForwardedAreIgnored() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Real-IP", "203.0.113.7");
        headers.add("Forwarded", "for=203.0.113.7;proto=https");

        Map<String, String> body = probe(headers);

        assertEquals("127.0.0.1", body.get("canonical"));
        assertEquals(body.get("canonical"), body.get("rateIp"));
        assertEquals(body.get("canonical"), body.get("auditIp"));
    }

    @Test
    void allowlistedSpoofCannotPromoteUntrustedPeer() {
        // Even a header claiming a "trusted-looking" address changes nothing for an
        // untrusted peer: there is no implicit private-range trust.
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "10.0.0.9");

        Map<String, String> body = probe(headers);

        assertEquals("127.0.0.1", body.get("canonical"));
        assertEquals(body.get("canonical"), body.get("rateIp"));
        assertEquals(body.get("canonical"), body.get("auditIp"));
    }
}
