package com.campuschat.server.service;

import com.campuschat.dao.MessageDao;
import com.campuschat.dao.UserDao;
import com.campuschat.dto.MessageDto;
import com.campuschat.model.Batch;
import com.campuschat.model.Message;
import com.campuschat.model.MessageType;
import com.campuschat.model.User;
import com.campuschat.server.network.ConnectionContext;
import com.campuschat.server.state.BatchRoomRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * Service handling chat persistence and real-time broadcasts.
 */
public class MessageService {

    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);

    private final MessageDao messageDao;
    private final UserDao userDao;
    private final BatchRoomRegistry batchRoomRegistry;

    public MessageService(MessageDao messageDao, UserDao userDao, BatchRoomRegistry batchRoomRegistry) {
        this.messageDao = messageDao;
        this.userDao = userDao;
        this.batchRoomRegistry = batchRoomRegistry;
    }

    /**
     * Broadcasts a chat message to all online members in the batch room.
     */
    public boolean processChatMessage(ConnectionContext ctx, Integer batchId, String content) {
        if (ctx == null || !ctx.isAuthenticated()) {
            if (ctx != null) ctx.send("ERROR|Not authenticated");
            return false;
        }

        if (content == null || content.isBlank()) {
            ctx.send("ERROR|Message content cannot be empty");
            return false;
        }

        // Cap message length to 2000 characters (PRD Section 13)
        if (content.length() > 2000) {
            ctx.send("ERROR|Message exceeds 2000 characters limit");
            return false;
        }

        // Validate user belongs to this batch (PRD FR-3: Users only chat in their own batch)
        if (ctx.getBatchId() == null || !ctx.getBatchId().equals(batchId)) {
            ctx.send("ERROR|Unauthorized batch room access");
            return false;
        }

        Optional<User> senderOpt = userDao.findById(ctx.getUserId());
        if (senderOpt.isEmpty()) {
            ctx.send("ERROR|Sender account not found");
            return false;
        }

        User sender = senderOpt.get();
        Batch batch = sender.getBatch();

        // 1. Persist the message in the database
        Message message = new Message(sender, batch, content, MessageType.CHAT);
        messageDao.save(message);

        // 2. Build DTO and protocol string
        MessageDto dto = MessageDao.toDto(message);
        String protocolMsg = dto.toProtocolString();

        // 3. Broadcast to all online batch members
        batchRoomRegistry.broadcast(batchId, protocolMsg);
        logger.debug("Broadcasted message from {} to batch {}", sender.getUsername(), batchId);
        return true;
    }
}
