package com.campuschat.server.service;

import com.campuschat.dao.SessionDao;
import com.campuschat.model.Batch;
import com.campuschat.model.User;
import com.campuschat.model.UserSession;
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
}
