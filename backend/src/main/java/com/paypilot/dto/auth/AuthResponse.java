package com.paypilot.dto.auth;

public record AuthResponse(
    String token,
    String tokenType,
    long expiresInMs,
    UserSummaryDto user
) {
    public static AuthResponse bearer(String token, long expiresInMs, UserSummaryDto user) {
        return new AuthResponse(token, "Bearer", expiresInMs, user);
    }
}

