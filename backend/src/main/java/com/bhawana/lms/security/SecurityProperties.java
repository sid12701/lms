package com.bhawana.lms.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private static final String DEFAULT_BOOTSTRAP_USERNAME = "ops.admin";
    private static final String DEFAULT_BOOTSTRAP_PASSWORD = "ChangeMe123!";
    private static final List<String> DEFAULT_BOOTSTRAP_ROLES = List.of("SYSTEM_ADMIN", "OPS_USER");

    @Valid
    private final BootstrapUser bootstrapUser = new BootstrapUser();

    @Valid
    private final Jwt jwt = new Jwt();

    public BootstrapUser getBootstrapUser() {
        return bootstrapUser;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public static class BootstrapUser {

        @NotBlank
        private String username;

        @NotBlank
        private String password;

        @NotEmpty
        private List<String> roles = new ArrayList<>();

        public String getUsername() {
            if (username == null || username.isBlank()) {
                return DEFAULT_BOOTSTRAP_USERNAME;
            }
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            if (password == null || password.isBlank()) {
                return DEFAULT_BOOTSTRAP_PASSWORD;
            }
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            if (roles == null || roles.isEmpty()) {
                return DEFAULT_BOOTSTRAP_ROLES;
            }
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }

    public static class Jwt {

        @NotBlank
        private String secret;

        @NotBlank
        private String issuer = "bhawana-lms";

        private Duration ttl = Duration.ofMinutes(30);

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }
}
