package com.campuschat.dto;

import com.campuschat.model.MessageType;
import java.time.Instant;

/**
 * Clean data transfer object for messages, decoupled from Hibernate Sessions.
 */
public record MessageDto(
    Integer messageId,
    Integer senderId,
    String senderUsername,
    Integer batchId,
    String content,
    Instant timestamp,
    MessageType type
) {
    /**
     * Protocol format: MSG|type|sender|batchId|content|timestamp
     */
    public String toProtocolString() {
        return "MSG|" + type.name() + "|" + senderUsername + "|" +
                (batchId != null ? batchId : "") + "|" +
                content + "|" + timestamp.toString();
    }
}
