package com.campuschat.server.state;

import com.campuschat.server.network.ConnectionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry tracking active authenticated sessions in memory.
 */
public class SessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SessionRegistry.class);

    private final ConcurrentHashMap<String, ConnectionContext> tokenToContext = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ConnectionContext> userIdToContext = new ConcurrentHashMap<>();

    public void register(String token, Integer userId, ConnectionContext ctx) {
        if (token != null && ctx != null) {
            tokenToContext.put(token, ctx);
            ctx.setSessionToken(token);
        }
        if (userId != null && ctx != null) {
            userIdToContext.put(userId, ctx);
            ctx.setUserId(userId);
        }
        logger.debug("Registered session for userId: {}, token: {}", userId, token);
    }

    public void unregister(ConnectionContext ctx) {
        if (ctx == null) return;
        if (ctx.getSessionToken() != null) {
            tokenToContext.remove(ctx.getSessionToken());
        }
        if (ctx.getUserId() != null) {
            userIdToContext.remove(ctx.getUserId());
        }
        logger.debug("Unregistered session for userId: {}", ctx.getUserId());
    }

    public ConnectionContext getContextByToken(String token) {
        return token != null ? tokenToContext.get(token) : null;
    }

    public ConnectionContext getContextByUserId(Integer userId) {
        return userId != null ? userIdToContext.get(userId) : null;
    }

    public boolean isUserOnline(Integer userId) {
        return userId != null && userIdToContext.containsKey(userId);
    }

    public int getActiveSessionCount() {
        return tokenToContext.size();
    }
}
