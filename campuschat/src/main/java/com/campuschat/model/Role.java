package com.campuschat.model;

/**
 * User roles in the Campus Chat System.
 */
public enum Role {
    STUDENT,
    FACULTY,
    ADMIN;

    public static Role fromString(String roleStr) {
        if (roleStr == null) return STUDENT;
        try {
            return Role.valueOf(roleStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Default or fallback
            return STUDENT;
        }
    }
}
