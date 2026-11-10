package com.paypilot.dto.auth;

import com.paypilot.model.User;
import com.paypilot.model.UserRole;

/**
 * Public user projection. Never exposes passwordHash.
 */
public record UserSummaryDto(
    Long id,
    String name,
    String email,
    UserRole role
) {
    public static UserSummaryDto from(User user) {
        return new UserSummaryDto(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}

