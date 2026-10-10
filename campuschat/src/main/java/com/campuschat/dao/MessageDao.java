package com.campuschat.dao;

import com.campuschat.dto.MessageDto;
import com.campuschat.model.Message;
import com.campuschat.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageDao {

    private static final Logger logger = LoggerFactory.getLogger(MessageDao.class);

    /**
     * Query 4: Store message
     */
    public Message save(Message message) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.persist(message);
            tx.commit();
            return message;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to save message: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Query 2: Load recent history (last 50 messages)
     * JOIN FETCH prevents LazyInitializationException on sender.
     */
    public List<MessageDto> findRecentHistory(Integer batchId, int limit) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            List<Message> messages = session.createQuery(
                    "from Message m join fetch m.sender where m.batch.id = :bid order by m.timestamp desc",
                    Message.class)
                    .setParameter("bid", batchId)
                    .setMaxResults(limit)
                    .list();

            // Reverse list so client receives in chronological order
            List<MessageDto> dtos = new ArrayList<>();
            for (Message m : messages) {
                dtos.add(toDto(m));
            }
            Collections.reverse(dtos);
            return dtos;
        }
    }

    /**
     * Query 10: Count messages today (for admin stats)
     */
    public long countMessagesSince(Instant since) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Long count = session.createQuery(
                    "select count(m) from Message m where m.timestamp >= :since",
                    Long.class)
                    .setParameter("since", since)
                    .uniqueResult();
            return count != null ? count : 0L;
        }
    }

    public static MessageDto toDto(Message message) {
        if (message == null) return null;
        Integer senderId = message.getSender() != null ? message.getSender().getUserId() : null;
        String senderUsername = message.getSender() != null ? message.getSender().getUsername() : "Unknown";
        Integer batchId = message.getBatch() != null ? message.getBatch().getBatchId() : null;
        return new MessageDto(
                message.getMessageId(),
                senderId,
                senderUsername,
                batchId,
                message.getContent(),
                message.getTimestamp(),
                message.getType()
        );
    }
}
