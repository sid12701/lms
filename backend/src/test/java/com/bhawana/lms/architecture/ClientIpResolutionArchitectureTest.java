package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H03 guardrail: the canonical client IP is the only client IP.
 *
 * <p>{@code HttpServletRequest.getRemoteAddr()} is the raw socket peer. Behind an ingress it is
 * the proxy, not the caller. H03 established one canonical resolution path — the edge filter
 * computes it once and {@link com.bhawana.lms.common.web.ClientIpAddresses#resolve} reads it —
 * shared by authentication, IP allowlists, rate limiting and audit. A call site that reaches for
 * the raw peer instead silently reintroduces the finding: allowlists compare the wrong address,
 * rate limits bucket every caller together, and audit rows attribute actions to the proxy.
 *
 * <p>This is invisible to a type checker, a linter and a formatter. It is one method call that
 * compiles, reads naturally and is wrong only in deployments that have an edge in front.
 */
class ClientIpResolutionArchitectureTest {

    /** Classes permitted to read the raw socket peer, with the reason each one needs it. */
    private static final Map<String, String> RAW_PEER_ALLOWLIST = Map.of(
            "com.bhawana.lms.common.web.ClientIpAddresses",
            "Owns canonicalization; falls back to the peer when the filter attribute is absent.",
            "com.bhawana.lms.security.ClientIpResolutionFilter",
            "Computes the canonical attribute from the peer plus the trusted forwarding chain."
    );

    @Test
    void rawSocketPeerIsReadOnlyByTheCanonicalResolver() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            if (RAW_PEER_ALLOWLIST.containsKey(javaClass.getName())) {
                continue;
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getOwner().equals(javaClass)) {
                    continue;
                }
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (!"getRemoteAddr".equals(call.getName())) {
                        continue;
                    }
                    if (!call.getTargetOwner().getPackageName().startsWith("jakarta.servlet")) {
                        continue;
                    }
                    violations.add(javaClass.getName() + "#" + method.getName());
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("""
                    Raw socket peer read outside the canonical resolver (H03). \
                    Use ClientIpAddresses.resolve(request) so authentication, allowlists, rate \
                    limits and audit all agree on one client IP. Violations: """ + violations);
        }
    }
}
