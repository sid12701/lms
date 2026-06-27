package com.bhawana.lms.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthApiResponses {

    private AuthApiResponses() {
    }

    public record RefreshFailureResponse(
            String code,
            String message
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record ClientCredentialsRequest(
            @NotBlank String clientId,
            @NotBlank String clientSecret
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {
    }
}
