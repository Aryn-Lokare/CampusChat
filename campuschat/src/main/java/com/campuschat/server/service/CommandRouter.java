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

    public CommandRouter(AuthService authService, SessionService sessionService, MessageService messageService) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.messageService = messageService;
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
                case "CHAT" -> handleChat(ctx, parts);
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
    }

    private void handleChat(ConnectionContext ctx, String[] parts) {
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: CHAT|batchId|content");
            return;
        }

        try {
            Integer batchId = Integer.parseInt(parts[1].trim());
            // Reconstruct content if it contained pipe symbols
            String content;
            if (parts.length == 3) {
                content = parts[2];
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < parts.length; i++) {
                    if (i > 2) sb.append("|");
                    sb.append(parts[i]);
                }
                content = sb.toString();
            }

            messageService.processChatMessage(ctx, batchId, content);
        } catch (NumberFormatException e) {
            ctx.send("ERROR|Invalid batchId format");
        }
    }

    private void handleLogout(ConnectionContext ctx) {
        sessionService.terminateSession(ctx);
        ctx.send("OK|LOGGED_OUT");
        ctx.close();
    }
}
