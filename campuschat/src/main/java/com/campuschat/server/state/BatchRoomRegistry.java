package com.campuschat.server.state;

import com.campuschat.server.network.ConnectionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry managing active connections scoped to academic batches.
 */
public class BatchRoomRegistry {

    private static final Logger logger = LoggerFactory.getLogger(BatchRoomRegistry.class);

    // Maps batchId -> Set of active ConnectionContexts
    private final ConcurrentHashMap<Integer, Set<ConnectionContext>> batchRooms = new ConcurrentHashMap<>();

    public void join(Integer batchId, ConnectionContext ctx) {
        if (batchId == null || ctx == null) return;
        batchRooms.computeIfAbsent(batchId, k -> ConcurrentHashMap.newKeySet()).add(ctx);
        ctx.setBatchId(batchId);
        logger.debug("User {} joined batch room {}", ctx.getUsername(), batchId);
    }

    public void leave(Integer batchId, ConnectionContext ctx) {
        if (batchId == null || ctx == null) return;
        Set<ConnectionContext> members = batchRooms.get(batchId);
        if (members != null) {
            members.remove(ctx);
            if (members.isEmpty()) {
                batchRooms.remove(batchId, Collections.emptySet());
            }
        }
    }

    public void remove(ConnectionContext ctx) {
        if (ctx == null) return;
        if (ctx.getBatchId() != null) {
            leave(ctx.getBatchId(), ctx);
        }
        for (Set<ConnectionContext> members : batchRooms.values()) {
            members.remove(ctx);
        }
    }

    public Set<ConnectionContext> getMembers(Integer batchId) {
        if (batchId == null) return Collections.emptySet();
        Set<ConnectionContext> members = batchRooms.get(batchId);
        return members != null ? members : Collections.emptySet();
    }

    /**
     * Broadcasts a protocol line to all currently online members of a batch room.
     */
    public void broadcast(Integer batchId, String message) {
        if (batchId == null || message == null) return;
        Set<ConnectionContext> members = batchRooms.get(batchId);
        if (members != null) {
            for (ConnectionContext member : members) {
                member.send(message);
            }
        }
    }

    public int getOnlineUserCount(Integer batchId) {
        Set<ConnectionContext> members = batchRooms.get(batchId);
        return members != null ? members.size() : 0;
    }

    public int getTotalOnlineCount() {
        int count = 0;
        for (Set<ConnectionContext> members : batchRooms.values()) {
            count += members.size();
        }
        return count;
    }
}
