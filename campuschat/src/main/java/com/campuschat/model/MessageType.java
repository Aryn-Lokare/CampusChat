package com.campuschat.model;

/**
 * Message classification in the Campus Chat System.
 * ANNOUNCE has highest outbound priority, followed by EVENT, then CHAT.
 */
public enum MessageType {
    CHAT(3),
    EVENT(2),
    ANNOUNCE(1);

    private final int priority;

    MessageType(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    public static MessageType fromString(String typeStr) {
        if (typeStr == null) return CHAT;
        try {
            return MessageType.valueOf(typeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CHAT;
        }
    }
}
