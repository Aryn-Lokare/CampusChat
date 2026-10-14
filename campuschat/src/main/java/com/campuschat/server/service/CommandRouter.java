package com.campuschat.server.service;

import com.campuschat.model.User;
import com.campuschat.server.network.ConnectionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses and routes line-based protocol commands received from clients.
 * Commands follow the pipe-delimited format: COMMAND|arg1|arg2...
 */
public class CommandRouter {

    private static final Logger logger = LoggerFactory.getLogger(CommandRouter.class);

    private final AuthService authService;
    private final SessionService sessionService;
    private final MessageService messageService;
    private final RateLimiter rateLimiter;

    public CommandRouter(AuthService authService, SessionService sessionService, MessageService messageService, RateLimiter rateLimiter) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.rateLimiter = rateLimiter;
    }

    public CommandRouter(AuthService authService, SessionService sessionService, MessageService messageService) {
        this(authService, sessionService, messageService, null);
    }

    /**
     * Dispatches a single framed protocol command line.
     * Execution runs on worker threads, never on the NIO Selector thread.
     */
    public void route(ConnectionContext ctx, String line) {
        if (ctx == null || line == null || line.isBlank()) return;

        try {
            // Split line by '|'
            String[] parts = line.split("\\|", -1);
            String command = parts[0].trim().toUpperCase();

            switch (command) {
                case "LOGIN" -> handleLogin(ctx, parts);
                case "RECONNECT" -> handleReconnect(ctx, parts);
                case "CHAT" -> handleChat(ctx, parts);
                case "ANNOUNCE" -> handleAnnounce(ctx, parts);
                case "EVENT" -> handleEvent(ctx, parts);
                case "HISTORY" -> handleHistory(ctx, parts);
                case "LOGOUT" -> handleLogout(ctx);
                default -> {
                    logger.warn("Unknown command from client {}: {}", ctx.getUsername(), command);
                    ctx.send("ERROR|Unknown command: " + command);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing command from client: {}", e.getMessage(), e);
            ctx.send("ERROR|Internal server error: " + e.getMessage());
        }
    }

    private void handleLogin(ConnectionContext ctx, String[] parts) {
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: LOGIN|username|password");
            return;
        }

        String identifier = parts[1];
        String password = parts[2];

        AuthService.AuthResult result = authService.authenticate(identifier, password);
        if (!result.success()) {
            ctx.send("ERROR|" + result.errorReason());
            return;
        }

        User user = result.user();
        String sessionToken = sessionService.createSession(user, ctx);
        ctx.send("OK|" + sessionToken);

        // FR-12: Load and stream chat history (last 50 messages) upon room entry
        if (user.getBatch() != null) {
            messageService.sendRecentHistory(ctx, user.getBatch().getBatchId(), 50);
        }
    }

    private void handleReconnect(ConnectionContext ctx, String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) {
            ctx.send("ERROR|Usage: RECONNECT|sessionToken");
            return;
        }

        String token = parts[1].trim();
        boolean restored = sessionService.reconnect(token, ctx);
        if (restored) {
            ctx.send("OK|RECONNECTED");
            // FR-8: Flush queued undelivered messages in timestamp order
            messageService.syncQueuedMessages(ctx);
        }
    }

    private void handleChat(ConnectionContext ctx, String[] parts) {
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: CHAT|batchId|content");
            return;
        }

        // FR-10: Token-bucket rate limiting for student connections (Faculty are exempt)
        if (rateLimiter != null && !rateLimiter.tryConsume(ctx)) {
            ctx.send("ERROR|Rate limit exceeded: Please slow down");
            return;
        }

        try {
            Integer batchId = Integer.parseInt(parts[1].trim());
            String content = extractTrailingContent(parts, 2);
            messageService.processChatMessage(ctx, batchId, content);
        } catch (NumberFormatException e) {
            ctx.send("ERROR|Invalid batchId format");
        }
    }

    private void handleAnnounce(ConnectionContext ctx, String[] parts) {
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: ANNOUNCE|batchId|content");
            return;
        }

        try {
            Integer batchId = Integer.parseInt(parts[1].trim());
            String content = extractTrailingContent(parts, 2);
            messageService.processAnnouncement(ctx, batchId, content);
        } catch (NumberFormatException e) {
            ctx.send("ERROR|Invalid batchId format");
        }
    }

    private void handleEvent(ConnectionContext ctx, String[] parts) {
        if (parts.length < 4) {
            ctx.send("ERROR|Usage: EVENT|batchId|title|datetime");
            return;
        }

        try {
            Integer batchId = Integer.parseInt(parts[1].trim());
            String title = parts[2];
            String datetime = extractTrailingContent(parts, 3);
            messageService.processEventNotice(ctx, batchId, title, datetime);
        } catch (NumberFormatException e) {
            ctx.send("ERROR|Invalid batchId format");
        }
    }

    private void handleHistory(ConnectionContext ctx, String[] parts) {
        if (parts.length < 2) {
            ctx.send("ERROR|Usage: HISTORY|batchId");
            return;
        }

        try {
            Integer batchId = Integer.parseInt(parts[1].trim());
            messageService.sendRecentHistory(ctx, batchId, 50);
        } catch (NumberFormatException e) {
            ctx.send("ERROR|Invalid batchId format");
        }
    }

    private void handleLogout(ConnectionContext ctx) {
        sessionService.terminateSession(ctx);
        ctx.send("OK|LOGGED_OUT");
        ctx.close();
    }

    private String extractTrailingContent(String[] parts, int startIndex) {
        if (parts.length == startIndex + 1) {
            return parts[startIndex];
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < parts.length; i++) {
            if (i > startIndex) sb.append("|");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
}
