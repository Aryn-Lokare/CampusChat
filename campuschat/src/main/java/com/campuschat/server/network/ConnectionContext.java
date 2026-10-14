package com.campuschat.server.network;

import com.campuschat.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Encapsulates the state of an active client socket connection.
 * Holds line framing buffers, outbound priority write queues (FR-9), and authenticated session metadata.
 */
public class ConnectionContext {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionContext.class);

    private final SocketChannel socketChannel;
    private final LineBuffer lineBuffer = new LineBuffer();
    private final PriorityBlockingQueue<OutboundMessage> outboundQueue = new PriorityBlockingQueue<>();
    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final NioServer server;

    private SelectionKey selectionKey;
    private ByteBuffer currentWritingBuffer = null;

    // Authenticated user state
    private Integer userId;
    private String username;
    private Role role;
    private Integer batchId;
    private String sessionToken;

    public ConnectionContext(SocketChannel socketChannel, NioServer server) {
        this.socketChannel = socketChannel;
        this.server = server;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public LineBuffer getLineBuffer() {
        return lineBuffer;
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Integer getBatchId() {
        return batchId;
    }

    public void setBatchId(Integer batchId) {
        this.batchId = batchId;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public boolean isAuthenticated() {
        return userId != null && sessionToken != null;
    }

    /**
     * Enqueues a newline-terminated string to be sent to this client with priority ordering (FR-9).
     * Triggers the NIO Selector loop to enable OP_WRITE.
     */
    public void send(String message) {
        if (!socketChannel.isOpen() || message == null) return;

        long seq = sequenceGenerator.incrementAndGet();
        OutboundMessage msg = OutboundMessage.of(message, seq);
        outboundQueue.add(msg);

        if (server != null) {
            server.requestWrite(this);
        }
    }

    public void send(OutboundMessage outboundMessage) {
        if (!socketChannel.isOpen() || outboundMessage == null) return;

        outboundQueue.add(outboundMessage);

        if (server != null) {
            server.requestWrite(this);
        }
    }

    public boolean hasPendingWrites() {
        return currentWritingBuffer != null || !outboundQueue.isEmpty();
    }

    public int getOutboundQueueSize() {
        return outboundQueue.size();
    }

    public OutboundMessage peekOutbound() {
        return outboundQueue.peek();
    }

    /**
     * Drains pending outbound bytes into the SocketChannel non-blockingly.
     * Drains highest priority messages first (FR-9).
     * Handles partial socket writes cleanly without stalling other clients.
     *
     * @return true if all pending bytes were written; false if socket buffer is full (partial write)
     */
    public boolean drain() throws IOException {
        while (true) {
            if (currentWritingBuffer == null) {
                OutboundMessage msg = outboundQueue.poll();
                if (msg == null) {
                    return true; // No more data to write
                }
                currentWritingBuffer = msg.getBuffer();
            }

            socketChannel.write(currentWritingBuffer);

            if (currentWritingBuffer.hasRemaining()) {
                // Socket output buffer is full; keep currentWritingBuffer and continue later
                return false;
            }

            // Buffer fully written, reset for next item
            currentWritingBuffer = null;
        }
    }

    public void close() {
        try {
            if (selectionKey != null) {
                selectionKey.cancel();
            }
            if (socketChannel != null && socketChannel.isOpen()) {
                socketChannel.close();
            }
        } catch (IOException e) {
            logger.debug("Error closing connection: {}", e.getMessage());
        }
    }
}
