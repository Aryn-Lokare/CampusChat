package com.campuschat.server.service;

import com.campuschat.dao.SessionDao;
import com.campuschat.model.Batch;
import com.campuschat.model.User;
import com.campuschat.model.UserSession;
import com.campuschat.model.UserStatus;
import com.campuschat.server.network.ConnectionContext;
import com.campuschat.server.state.BatchRoomRegistry;
import com.campuschat.server.state.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * Service managing user sessions, lifecycle, and room association.
 */
public class SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);

    private final SessionDao sessionDao;
    private final SessionRegistry sessionRegistry;
    private final BatchRoomRegistry batchRoomRegistry;

    public SessionService(SessionDao sessionDao, SessionRegistry sessionRegistry, BatchRoomRegistry batchRoomRegistry) {
        this.sessionDao = sessionDao;
        this.sessionRegistry = sessionRegistry;
        this.batchRoomRegistry = batchRoomRegistry;
    }

    /**
     * Creates an authenticated session for the given user, persists it,
     * updates in-memory registries, and joins the user to their batch room.
     */
    public String createSession(User user, ConnectionContext ctx) {
        String token = UUID.randomUUID().toString();
        Batch batch = user.getBatch();
        Integer batchId = batch != null ? batch.getBatchId() : null;

        UserSession userSession = new UserSession(token, user, batch, "Desktop App");
        sessionDao.save(userSession);

        ctx.setUserId(user.getUserId());
        ctx.setUsername(user.getUsername());
        ctx.setRole(user.getRole());
        ctx.setBatchId(batchId);
        ctx.setSessionToken(token);

        sessionRegistry.register(token, user.getUserId(), ctx);

        if (batchId != null) {
            batchRoomRegistry.join(batchId, ctx);
        }

        logger.info("Session created for user: {} (Role: {}, Batch: {})", user.getUsername(), user.getRole(), batchId);
        return token;
    }

    public void terminateSession(ConnectionContext ctx) {
        if (ctx == null) return;
        String token = ctx.getSessionToken();
        if (token != null) {
            sessionDao.deactivateSession(token);
        }
        batchRoomRegistry.remove(ctx);
        sessionRegistry.unregister(ctx);
        logger.info("Terminated session for user: {}", ctx.getUsername());
    }

    public Optional<UserSession> validateToken(String token) {
        return sessionDao.findActiveByToken(token);
    }

    /**
     * FR-8: Reconnects an existing session using a valid session token.
     * Restores user state without requiring password re-entry, updates lastSeenTime
     * with optimistic lock validation, replaces any stale connection context in registries,
     * and rejoins the user to their batch room.
     */
    public boolean reconnect(String token, ConnectionContext ctx) {
        if (token == null || token.isBlank() || ctx == null) {
            if (ctx != null) ctx.send("ERROR|Invalid session token");
            return false;
        }

        UserSession userSession;
        try {
            Optional<UserSession> sessionOpt = sessionDao.validateAndTouchSession(token);
            if (sessionOpt.isEmpty()) {
                ctx.send("ERROR|Invalid or expired session token");
                return false;
            }
            userSession = sessionOpt.get();
        } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException ole) {
            logger.warn("Optimistic concurrency conflict during reconnect for token: {}", token);
            ctx.send("ERROR|Concurrent session conflict detected");
            return false;
        } catch (Exception e) {
            logger.error("Error validating session during reconnect: {}", e.getMessage(), e);
            ctx.send("ERROR|Internal server error during reconnect");
            return false;
        }

        User user = userSession.getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            ctx.send("ERROR|Account is " + user.getStatus().name().toLowerCase());
            return false;
        }

        // Clean up any stale connection for this token or user in memory
        ConnectionContext oldCtx = sessionRegistry.getContextByToken(token);
        if (oldCtx != null && oldCtx != ctx) {
            batchRoomRegistry.remove(oldCtx);
            sessionRegistry.unregister(oldCtx);
            try {
                oldCtx.close();
            } catch (Exception ignored) {}
        }

        Integer batchId = userSession.getBatch() != null ? userSession.getBatch().getBatchId() : null;

        ctx.setUserId(user.getUserId());
        ctx.setUsername(user.getUsername());
        ctx.setRole(user.getRole());
        ctx.setBatchId(batchId);
        ctx.setSessionToken(token);

        sessionRegistry.register(token, user.getUserId(), ctx);

        if (batchId != null) {
            batchRoomRegistry.join(batchId, ctx);
        }

        logger.info("Session reconnected for user: {} (Role: {}, Batch: {})", user.getUsername(), user.getRole(), batchId);
        return true;
    }
}
