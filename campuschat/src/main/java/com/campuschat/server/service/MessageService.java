package com.campuschat.server.service;

import com.campuschat.dao.MessageDao;
import com.campuschat.dao.QueueDao;
import com.campuschat.dao.UserDao;
import com.campuschat.dto.MessageDto;
import com.campuschat.model.Batch;
import com.campuschat.model.Message;
import com.campuschat.model.MessageType;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.server.network.ConnectionContext;
import com.campuschat.server.state.BatchRoomRegistry;
import com.campuschat.server.state.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service handling chat, announcements, event notices, offline message queuing (FR-7),
 * and sync catch-up (FR-8) with Role-Based Access Control.
 */
public class MessageService {

    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);
    public static final int MAX_CONTENT_LENGTH = 500;
    public static final int DEFAULT_HISTORY_LIMIT = 50;

    private final MessageDao messageDao;
    private final UserDao userDao;
    private final QueueDao queueDao;
    private final SessionRegistry sessionRegistry;
    private final BatchRoomRegistry batchRoomRegistry;

    public MessageService(MessageDao messageDao, UserDao userDao, QueueDao queueDao,
                          SessionRegistry sessionRegistry, BatchRoomRegistry batchRoomRegistry) {
        this.messageDao = messageDao;
        this.userDao = userDao;
        this.queueDao = queueDao;
        this.sessionRegistry = sessionRegistry;
        this.batchRoomRegistry = batchRoomRegistry;
    }

    /**
     * Backward-compatible constructor
     */
    public MessageService(MessageDao messageDao, UserDao userDao, BatchRoomRegistry batchRoomRegistry) {
        this(messageDao, userDao, new QueueDao(), new SessionRegistry(), batchRoomRegistry);
    }

    /**
     * FR-4: Broadcasts a regular chat message to online members and queues for offline members.
     */
    public boolean processChatMessage(ConnectionContext ctx, Integer batchId, String content) {
        if (!validateAuthentication(ctx)) return false;

        if (ctx.getRole() == Role.ADMIN) {
            ctx.send("ERROR|Permission denied: Admins cannot participate in batch chat");
            return false;
        }

        if (!validateBatchAccess(ctx, batchId)) return false;
        if (!validateContent(ctx, content)) return false;

        return persistBroadcastAndQueue(ctx, batchId, content.trim(), MessageType.CHAT);
    }

    /**
     * FR-5: Broadcasts a priority announcement to online members and queues for offline members.
     */
    public boolean processAnnouncement(ConnectionContext ctx, Integer batchId, String content) {
        if (!validateAuthentication(ctx)) return false;

        if (ctx.getRole() != Role.FACULTY) {
            ctx.send("ERROR|Permission denied: Only Faculty can post announcements");
            return false;
        }

        if (!validateBatchAccess(ctx, batchId)) return false;
        if (!validateContent(ctx, content)) return false;

        return persistBroadcastAndQueue(ctx, batchId, content.trim(), MessageType.ANNOUNCE);
    }

    /**
     * FR-6: Broadcasts an event notice to online members and queues for offline members.
     */
    public boolean processEventNotice(ConnectionContext ctx, Integer batchId, String title, String datetime) {
        if (!validateAuthentication(ctx)) return false;

        if (ctx.getRole() != Role.FACULTY) {
            ctx.send("ERROR|Permission denied: Only Faculty can post event notices");
            return false;
        }

        if (!validateBatchAccess(ctx, batchId)) return false;

        if (title == null || title.isBlank() || datetime == null || datetime.isBlank()) {
            ctx.send("ERROR|Event title and datetime must not be empty");
            return false;
        }

        String eventContent = title.trim() + " | " + datetime.trim();
        if (eventContent.length() > MAX_CONTENT_LENGTH) {
            ctx.send("ERROR|Event content exceeds " + MAX_CONTENT_LENGTH + " characters limit");
            return false;
        }

        return persistBroadcastAndQueue(ctx, batchId, eventContent, MessageType.EVENT);
    }

    /**
     * FR-8: Flushes queued undelivered messages in timestamp order between SYNC_START and SYNC_END.
     * Bulk marks rows delivered in a single HQL update.
     */
    public int syncQueuedMessages(ConnectionContext ctx) {
        if (ctx == null || !ctx.isAuthenticated()) return 0;
        Integer userId = ctx.getUserId();

        List<MessageDto> pending = queueDao.findUndeliveredForUser(userId);
        ctx.send("SYNC_START");
        for (MessageDto dto : pending) {
            ctx.send(dto.toProtocolString());
        }

        if (!pending.isEmpty()) {
            int marked = queueDao.markDeliveredForUser(userId);
            logger.info("Flushed and marked delivered {} pending message(s) for user {}", marked, ctx.getUsername());
        }
        ctx.send("SYNC_END");
        return pending.size();
    }

    /**
     * FR-12: Sends recent chat history to the client.
     */
    public void sendRecentHistory(ConnectionContext ctx, Integer batchId, int limit) {
        if (!validateAuthentication(ctx)) return;
        if (ctx.getRole() == Role.ADMIN) return;
        if (!validateBatchAccess(ctx, batchId)) return;

        int count = limit > 0 ? limit : DEFAULT_HISTORY_LIMIT;
        List<MessageDto> history = messageDao.findRecentHistory(batchId, count);

        ctx.send("HISTORY_START|" + batchId + "|" + history.size());
        for (MessageDto dto : history) {
            ctx.send(dto.toProtocolString());
        }
        ctx.send("HISTORY_END|" + batchId);
        logger.debug("Sent {} history messages for batch {} to user {}", history.size(), batchId, ctx.getUsername());
    }

    private boolean persistBroadcastAndQueue(ConnectionContext ctx, Integer batchId, String content, MessageType type) {
        Optional<User> senderOpt = userDao.findById(ctx.getUserId());
        if (senderOpt.isEmpty()) {
            ctx.send("ERROR|Sender account not found");
            return false;
        }

        User sender = senderOpt.get();
        Batch batch = sender.getBatch();

        // 1. Persist the message (FR-14: Audit trail)
        Message message = new Message(sender, batch, content, type);
        messageDao.save(message);

        // 2. Build DTO and protocol string
        MessageDto dto = MessageDao.toDto(message);
        String protocolMsg = dto.toProtocolString();

        // 3. Real-time broadcast to online members
        batchRoomRegistry.broadcast(batchId, protocolMsg);

        // 4. FR-7: Offline Queuing - find active batch members not online and insert queue rows
        if (batchId != null) {
            List<User> batchUsers = userDao.findActiveUsersByBatchId(batchId);
            List<User> offlineUsers = new ArrayList<>();
            for (User u : batchUsers) {
                // Skip sender and any user currently marked online
                if (!u.getUserId().equals(sender.getUserId()) && !sessionRegistry.isUserOnline(u.getUserId())) {
                    offlineUsers.add(u);
                }
            }
            if (!offlineUsers.isEmpty()) {
                queueDao.enqueueForUsers(offlineUsers, message);
                logger.debug("Queued [{}] message for {} offline user(s)", type, offlineUsers.size());
            }
        }

        logger.info("Broadcasted [{}] from {} to batch {}", type, sender.getUsername(), batchId);
        return true;
    }

    private boolean validateAuthentication(ConnectionContext ctx) {
        if (ctx == null || !ctx.isAuthenticated()) {
            if (ctx != null) ctx.send("ERROR|Not authenticated");
            return false;
        }
        return true;
    }

    private boolean validateBatchAccess(ConnectionContext ctx, Integer batchId) {
        if (ctx.getBatchId() == null || !ctx.getBatchId().equals(batchId)) {
            ctx.send("ERROR|Unauthorized batch room access");
            return false;
        }
        return true;
    }

    private boolean validateContent(ConnectionContext ctx, String content) {
        if (content == null || content.isBlank()) {
            ctx.send("ERROR|Message content cannot be empty");
            return false;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            ctx.send("ERROR|Message exceeds " + MAX_CONTENT_LENGTH + " characters limit");
            return false;
        }
        return true;
    }
}
