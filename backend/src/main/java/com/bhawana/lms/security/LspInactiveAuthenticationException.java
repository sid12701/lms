package com.bhawana.lms.security;

import org.springframework.security.core.AuthenticationException;

public class LspInactiveAuthenticationException extends AuthenticationException {

    public LspInactiveAuthenticationException() {
        super("LSP is not active");
    }
}
