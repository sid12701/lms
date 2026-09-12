package edgetest;

import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.security.EdgeIpEmbeddedSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the IP each consumer would observe for the incoming request: the canonical value
 * (the exact {@code ClientIpAddresses.resolve} call the auth controllers, the allowlist
 * filter/service, and the audit writers share) and the rate-limiter bucket IP (real
 * {@link com.bhawana.lms.security.KeyStrategy} code path via
 * {@link EdgeIpEmbeddedSupport#rateBucketIp}).
 */
@RestController
@RequestMapping("/edge-probe")
public class EdgeProbeController {

    @GetMapping
    public Map<String, String> probe(HttpServletRequest request) {
        String canonical = ClientIpAddresses.resolve(request);
        String rateIp = EdgeIpEmbeddedSupport.rateBucketIp(request);
        // Audit services receive exactly what the controllers pass: resolve(request).
        String auditIp = ClientIpAddresses.resolve(request);
        String peer = request.getRemoteAddr();
        return Map.of(
                "canonical", canonical == null ? "<null>" : canonical,
                "rateIp", rateIp,
                "auditIp", auditIp == null ? "<null>" : auditIp,
                "peer", peer == null ? "<null>" : peer);
    }
}
