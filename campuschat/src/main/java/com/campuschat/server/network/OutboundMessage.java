package com.campuschat.server.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * FR-9: Represents a prioritized outbound message queued for delivery to a client socket.
 * Ordering priority:
 * 0 - HIGH_CONTROL: Immediate control messages (OK|, ERROR|, SYNC_START, HISTORY_START, STATS|)
 * 1 - ANNOUNCE: High-priority faculty broadcast messages (MSG|ANNOUNCE|...)
 * 2 - EVENT: Scheduled faculty event notices (MSG|EVENT|...)
 * 3 - CHAT: Regular student/faculty batch chat messages (MSG|CHAT|...)
 * 4 - LOW_CONTROL: End-of-stream control markers (SYNC_END, HISTORY_END)
 *
 * Ties between equal priorities are broken by timestamp and sequence number (FIFO).
 */
public class OutboundMessage implements Comparable<OutboundMessage> {

    public static final int PRIORITY_HIGH_CONTROL = 0;
    public static final int PRIORITY_ANNOUNCE     = 1;
    public static final int PRIORITY_EVENT        = 2;
    public static final int PRIORITY_CHAT         = 3;
    public static final int PRIORITY_LOW_CONTROL  = 4;

    private final int priority;
    private final long timestamp;
    private final long sequence;
    private final ByteBuffer buffer;
    private final String rawMessage;

    public OutboundMessage(int priority, long timestamp, long sequence, ByteBuffer buffer, String rawMessage) {
        this.priority = priority;
        this.timestamp = timestamp;
        this.sequence = sequence;
        this.buffer = buffer;
        this.rawMessage = rawMessage;
    }

    public static OutboundMessage of(String message, long sequence) {
        int priority = determinePriority(message);
        String payload = message.endsWith("\n") ? message : message + "\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long timestamp = System.currentTimeMillis();
        return new OutboundMessage(priority, timestamp, sequence, buffer, message);
    }

    public static int determinePriority(String message) {
        if (message == null) return PRIORITY_HIGH_CONTROL;

        if (message.startsWith("MSG|ANNOUNCE|")) {
            return PRIORITY_ANNOUNCE;
        } else if (message.startsWith("MSG|EVENT|")) {
            return PRIORITY_EVENT;
        } else if (message.startsWith("MSG|CHAT|")) {
            return PRIORITY_CHAT;
        } else if (message.equals("SYNC_END") || message.startsWith("HISTORY_END|")) {
            return PRIORITY_LOW_CONTROL;
        } else {
            return PRIORITY_HIGH_CONTROL;
        }
    }

    public int getPriority() {
        return priority;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getSequence() {
        return sequence;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    @Override
    public int compareTo(OutboundMessage other) {
        if (other == null) return -1;
        int p = Integer.compare(this.priority, other.priority);
        if (p != 0) return p;
        int t = Long.compare(this.timestamp, other.timestamp);
        if (t != 0) return t;
        return Long.compare(this.sequence, other.sequence);
    }

    @Override
    public String toString() {
        return "OutboundMessage{" +
                "priority=" + priority +
                ", sequence=" + sequence +
                ", rawMessage='" + (rawMessage != null && rawMessage.length() > 30 ? rawMessage.substring(0, 30) + "..." : rawMessage) + '\'' +
                '}';
    }
}
