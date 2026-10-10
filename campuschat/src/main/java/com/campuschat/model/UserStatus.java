package com.campuschat.model;

/**
 * Lifecycle and moderation status of a user.
 */
public enum UserStatus {
    PENDING,
    ACTIVE,
    BANNED;

    public static UserStatus fromString(String statusStr) {
        if (statusStr == null) return PENDING;
        try {
            return UserStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
