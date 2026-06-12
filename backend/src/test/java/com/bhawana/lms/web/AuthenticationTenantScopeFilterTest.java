package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.tenant.TenantAccessContext;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantDataAccessMode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthenticationTenantScopeFilterTest {

    private final AuthenticationTenantScopeFilter filter = new AuthenticationTenantScopeFilter();

    @BeforeEach
    @AfterEach
    void resetThreadState() {
        SecurityContextHolder.clearContext();
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void lspPrincipalGetsTenantScopeForTheRequestAndIsClearedAfter() throws Exception {
        UUID lspId = UUID.randomUUID();
        authenticate(jwtAuthentication(jwt -> jwt.claim("lspId", lspId.toString())));

        TenantAccessContext scopeDuringRequest = runFilterAndCaptureScope();

        assertThat(scopeDuringRequest).isNotNull();
        assertThat(scopeDuringRequest.mode()).isEqualTo(TenantDataAccessMode.TENANT);
        assertThat(scopeDuringRequest.lspId()).isEqualTo(lspId);
        assertThat(TenantDataAccessContextHolder.snapshot()).isNull();
    }

    @Test
    void internalPrincipalWithoutLspClaimGetsAdminScope() throws Exception {
        authenticate(jwtAuthentication(jwt -> jwt.claim("roles", List.of("SYSTEM_ADMIN"))));

        TenantAccessContext scopeDuringRequest = runFilterAndCaptureScope();

        assertThat(scopeDuringRequest).isNotNull();
        assertThat(scopeDuringRequest.mode()).isEqualTo(TenantDataAccessMode.ADMIN);
        assertThat(TenantDataAccessContextHolder.snapshot()).isNull();
    }

    @Test
    void unauthenticatedRequestStaysUnscopedSoDataAccessFailsClosed() throws Exception {
        TenantAccessContext scopeDuringRequest = runFilterAndCaptureScope();

        assertThat(scopeDuringRequest).isNull();
        assertThat(TenantDataAccessContextHolder.snapshot()).isNull();
    }

    @Test
    void anonymousAuthenticationStaysUnscoped() throws Exception {
        authenticate(new AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
        ));

        TenantAccessContext scopeDuringRequest = runFilterAndCaptureScope();

        assertThat(scopeDuringRequest).isNull();
    }

    @Test
    void preExistingScopeIsRestoredAfterTheRequest() throws Exception {
        UUID previousLspId = UUID.randomUUID();
        TenantDataAccessContextHolder.useTenant(previousLspId);
        authenticate(jwtAuthentication(jwt -> jwt.claim("roles", List.of("SYSTEM_ADMIN"))));

        TenantAccessContext scopeDuringRequest = runFilterAndCaptureScope();

        assertThat(scopeDuringRequest).isNotNull();
        assertThat(scopeDuringRequest.mode()).isEqualTo(TenantDataAccessMode.ADMIN);
        TenantAccessContext restored = TenantDataAccessContextHolder.snapshot();
        assertThat(restored).isNotNull();
        assertThat(restored.mode()).isEqualTo(TenantDataAccessMode.TENANT);
        assertThat(restored.lspId()).isEqualTo(previousLspId);
    }

    private TenantAccessContext runFilterAndCaptureScope() throws Exception {
        AtomicReference<TenantAccessContext> observedScope = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(
                new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void service(
                            jakarta.servlet.http.HttpServletRequest req,
                            jakarta.servlet.http.HttpServletResponse res
                    ) {
                        observedScope.set(TenantDataAccessContextHolder.snapshot());
                    }
                }
        );
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        return observedScope.get();
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static JwtAuthenticationToken jwtAuthentication(java.util.function.Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("test-user");
        claims.accept(builder);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }
}
