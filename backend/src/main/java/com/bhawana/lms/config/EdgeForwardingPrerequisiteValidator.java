package com.bhawana.lms.config;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.boot.autoconfigure.web.ServerProperties.Tomcat.Remoteip;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Raw-socket-peer prerequisite: fails application startup unless the effective
 * forwarding configuration provably leaves {@code HttpServletRequest.getRemoteAddr()} as
 * the raw socket peer, which is the trust anchor for canonical client-IP resolution.
 *
 * <p>Rejected, because each lets forwarding input rewrite the peer before the edge
 * resolution filter runs:
 * <ul>
 *   <li>an effective {@code server.forward-headers-strategy} of NATIVE (Boot installs a
 *   Tomcat {@code RemoteIpValve} that applies {@code X-Forwarded-For} to the peer) or
 *   FRAMEWORK (Boot registers Spring's {@code ForwardedHeaderFilter} at highest
 *   precedence, ahead of the whole security chain);</li>
 *   <li>explicit {@code server.tomcat.remoteip.remote-ip-header} /
 *   {@code server.tomcat.remoteip.protocol-header} settings, which install a
 *   {@code RemoteIpValve} even under strategy NONE.</li>
 * </ul>
 *
 * <p>An unset strategy is resolved exactly as Boot resolves it: NATIVE on a detected
 * cloud platform, NONE elsewhere — so a deployment that silently inherits NATIVE still
 * fails here instead of running with a broken trust boundary.
 */
@Configuration
public class EdgeForwardingPrerequisiteValidator {

    public EdgeForwardingPrerequisiteValidator(ServerProperties serverProperties, Environment environment) {
        validate(serverProperties, environment);
    }

    static void validate(ServerProperties serverProperties, Environment environment) {
        ForwardHeadersStrategy configured = serverProperties.getForwardHeadersStrategy();
        ForwardHeadersStrategy effective = configured != null
                ? configured
                : (CloudPlatform.getActive(environment) != null
                        ? ForwardHeadersStrategy.NATIVE
                        : ForwardHeadersStrategy.NONE);
        if (effective != ForwardHeadersStrategy.NONE) {
            throw new IllegalStateException(
                    "Edge trust boundary violated: server.forward-headers-strategy must be NONE so the "
                            + "socket peer stays the client-IP trust anchor, but the effective value is "
                            + effective
                            + (configured == null ? " (Boot cloud default; set the property explicitly)" : "")
                            + ". NATIVE installs a container RemoteIpValve and FRAMEWORK installs "
                            + "ForwardedHeaderFilter ahead of the security chain; both rewrite "
                            + "getRemoteAddr() before edge IP resolution runs.");
        }

        Remoteip remoteip = serverProperties.getTomcat().getRemoteip();
        if (StringUtils.hasText(remoteip.getRemoteIpHeader())
                || StringUtils.hasText(remoteip.getProtocolHeader())) {
            throw new IllegalStateException(
                    "Edge trust boundary violated: server.tomcat.remoteip.remote-ip-header / "
                            + "protocol-header install a container RemoteIpValve that rewrites "
                            + "getRemoteAddr() before edge IP resolution runs, even with strategy NONE. "
                            + "Unset both; the edge proxy contract covers client attribution instead.");
        }
    }
}
