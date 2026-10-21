package com.campuschat.server.service;

import com.campuschat.model.Role;
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
    private final RegistrationService registrationService;
    private final AdminService adminService;

    public CommandRouter(AuthService authService, SessionService sessionService, MessageService messageService,
                         RateLimiter rateLimiter, RegistrationService registrationService, AdminService adminService) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.rateLimiter = rateLimiter;
        this.registrationService = registrationService;
        this.adminService = adminService;
    }

    public CommandRouter(AuthService authService, SessionService sessionService, MessageService messageService,
                         RateLimiter rateLimiter, RegistrationService registrationService) {
        this(authService, sessionService, messageService, rateLimiter, registrationService, null);
    }

    public CommandRouter(AuthService authService, SessionService sessionService, MessageService messageService, RateLimiter rateLimiter) {
        this(authService, sessionService, messageService, rateLimiter, null, null);
    }

    public CommandRouter(AuthService authService, SessionService sessionService, MessageService messageService) {
        this(authService, sessionService, messageService, null, null, null);
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
                case "REGISTER" -> handleRegister(ctx, parts);
                case "VERIFY" -> handleVerify(ctx, parts);
                case "RESEND_VERIFY" -> handleResendVerify(ctx, parts);
                case "LOGIN" -> handleLogin(ctx, parts);
                case "RECONNECT" -> handleReconnect(ctx, parts);
                case "CHAT" -> handleChat(ctx, parts);
                case "ANNOUNCE" -> handleAnnounce(ctx, parts);
                case "EVENT" -> handleEvent(ctx, parts);
                case "HISTORY" -> handleHistory(ctx, parts);
                case "WHOAMI" -> handleWhoami(ctx);
                case "ADMIN_STATS" -> handleAdminStats(ctx);
                case "ADMIN_CREATE_BATCH" -> handleAdminCreateBatch(ctx, parts);
                case "ADMIN_DELETE_BATCH" -> handleAdminDeleteBatch(ctx, parts);
                case "ADMIN_SET_STATUS" -> handleAdminSetStatus(ctx, parts);
                case "ADMIN_SET_ROLE" -> handleAdminSetRole(ctx, parts);
                case "ADMIN_SET_BATCH" -> handleAdminSetBatch(ctx, parts);
                case "ADMIN_LIST_USERS" -> handleAdminListUsers(ctx);
                case "ADMIN_LIST_BATCHES" -> handleAdminListBatches(ctx);
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

    private void handleRegister(ConnectionContext ctx, String[] parts) {
        if (registrationService == null) {
            ctx.send("ERROR|Registration service is not configured");
            return;
        }

        if (parts.length < 5 || parts[4].isBlank()) {
            ctx.send("ERROR|Batch ID is required for student registration. Usage: REGISTER|username|email|password|batchId");
            return;
        }

        String username = parts[1];
        String email = parts[2];
        String password = parts[3];
        Integer batchId = null;
        try {
            batchId = Integer.parseInt(parts[4].trim());
        } catch (NumberFormatException e) {
            ctx.send("ERROR|Batch ID must be a valid number");
            return;
        }

        RegistrationService.RegistrationResult result = registrationService.register(username, email, password, batchId);
        if (result.success()) {
            ctx.send("OK|VERIFY_SENT");
        } else {
            ctx.send("ERROR|" + result.message());
        }
    }

    private void handleVerify(ConnectionContext ctx, String[] parts) {
        if (registrationService == null) {
            ctx.send("ERROR|Registration service is not configured");
            return;
        }

        if (parts.length < 2 || parts[1].isBlank()) {
            ctx.send("ERROR|Usage: VERIFY|token or VERIFY|email|token");
            return;
        }

        String token = (parts.length >= 3) ? parts[2].trim() : parts[1].trim();
        RegistrationService.VerificationResult result = registrationService.verify(token);
        if (result.success()) {
            ctx.send("OK|VERIFIED");
        } else {
            ctx.send("ERROR|" + result.message());
        }
    }

    private void handleResendVerify(ConnectionContext ctx, String[] parts) {
        if (registrationService == null) {
            ctx.send("ERROR|Registration service is not configured");
            return;
        }

        if (parts.length < 2 || parts[1].isBlank()) {
            ctx.send("ERROR|Usage: RESEND_VERIFY|email");
            return;
        }

        String email = parts[1].trim();
        RegistrationService.ResendResult result = registrationService.resendVerification(email);
        if (result.success()) {
            ctx.send("OK|VERIFY_SENT");
        } else {
            ctx.send("ERROR|" + result.message());
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

    private void handleWhoami(ConnectionContext ctx) {
        if (!ctx.isAuthenticated()) {
            ctx.send("ERROR|Not authenticated");
            return;
        }
        String roleName = ctx.getRole() != null ? ctx.getRole().name() : "STUDENT";
        Integer bId = ctx.getBatchId() != null ? ctx.getBatchId() : 1;
        ctx.send("ME|" + ctx.getUsername() + "|" + roleName + "|" + bId);
    }

    private void handleLogout(ConnectionContext ctx) {
        sessionService.terminateSession(ctx);
        ctx.send("OK|LOGGED_OUT");
        ctx.closeAfterWrite();
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

    private void handleAdminStats(ConnectionContext ctx) {
        if (!validateAdmin(ctx)) return;
        ctx.send(adminService.getLiveStatsString());
    }

    private void handleAdminCreateBatch(ConnectionContext ctx, String[] parts) {
        if (!validateAdmin(ctx)) return;
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: ADMIN_CREATE_BATCH|name|year");
            return;
        }
        AdminService.AdminResult res = adminService.createBatch(parts[1], parts[2]);
        if (res.success()) {
            ctx.send("OK|" + res.message());
        } else {
            ctx.send("ERROR|" + res.message());
        }
    }

    private void handleAdminDeleteBatch(ConnectionContext ctx, String[] parts) {
        if (!validateAdmin(ctx)) return;
        if (parts.length < 2) {
            ctx.send("ERROR|Usage: ADMIN_DELETE_BATCH|batchId");
            return;
        }
        AdminService.AdminResult res = adminService.deleteBatch(parts[1]);
        if (res.success()) {
            ctx.send("OK|" + res.message());
        } else {
            ctx.send("ERROR|" + res.message());
        }
    }

    private void handleAdminSetStatus(ConnectionContext ctx, String[] parts) {
        if (!validateAdmin(ctx)) return;
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: ADMIN_SET_STATUS|username|status");
            return;
        }
        AdminService.AdminResult res = adminService.setUserStatus(parts[1], parts[2]);
        if (res.success()) {
            ctx.send("OK|" + res.message());
        } else {
            ctx.send("ERROR|" + res.message());
        }
    }

    private void handleAdminSetRole(ConnectionContext ctx, String[] parts) {
        if (!validateAdmin(ctx)) return;
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: ADMIN_SET_ROLE|username|role");
            return;
        }
        AdminService.AdminResult res = adminService.setUserRole(parts[1], parts[2]);
        if (res.success()) {
            ctx.send("OK|" + res.message());
        } else {
            ctx.send("ERROR|" + res.message());
        }
    }

    private void handleAdminSetBatch(ConnectionContext ctx, String[] parts) {
        if (!validateAdmin(ctx)) return;
        if (parts.length < 3) {
            ctx.send("ERROR|Usage: ADMIN_SET_BATCH|username|batchId");
            return;
        }
        AdminService.AdminResult res = adminService.setUserBatch(parts[1], parts[2]);
        if (res.success()) {
            ctx.send("OK|" + res.message());
        } else {
            ctx.send("ERROR|" + res.message());
        }
    }

    private void handleAdminListUsers(ConnectionContext ctx) {
        if (!validateAdmin(ctx)) return;
        java.util.List<com.campuschat.dto.UserDto> users = adminService.getAllUsers();
        ctx.send("USERS_START|" + users.size());
        for (com.campuschat.dto.UserDto u : users) {
            ctx.send(String.format("USER_ROW|%d|%s|%s|%s|%s|%s",
                    u.userId(), u.username(), u.email(), u.role().name(), u.status().name(),
                    u.batchId() != null ? u.batchId() : "none"));
        }
        ctx.send("USERS_END");
    }

    private void handleAdminListBatches(ConnectionContext ctx) {
        if (!validateAdmin(ctx)) return;
        java.util.List<com.campuschat.dto.BatchDto> batches = adminService.getAllBatches();
        ctx.send("BATCHES_START|" + batches.size());
        for (com.campuschat.dto.BatchDto b : batches) {
            ctx.send(String.format("BATCH_ROW|%d|%s|%d", b.batchId(), b.name(), b.year()));
        }
        ctx.send("BATCHES_END");
    }

    private boolean validateAdmin(ConnectionContext ctx) {
        if (!ctx.isAuthenticated()) {
            ctx.send("ERROR|Not authenticated");
            return false;
        }
        if (ctx.getRole() != Role.ADMIN) {
            ctx.send("ERROR|Permission denied. Admin role required.");
            return false;
        }
        if (adminService == null) {
            ctx.send("ERROR|Admin service is not configured");
            return false;
        }
        return true;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public RegistrationService getRegistrationService() {
        return registrationService;
    }

    public AdminService getAdminService() {
        return adminService;
    }
}
