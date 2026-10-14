package com.campuschat.dao;

import com.campuschat.dto.MessageDto;
import com.campuschat.model.Message;
import com.campuschat.model.MessageQueue;
import com.campuschat.model.User;
import com.campuschat.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class QueueDao {

    private static final Logger logger = LoggerFactory.getLogger(QueueDao.class);

    /**
     * Enqueue a message for multiple offline users in one batch/transaction
     */
    public void enqueueForUsers(List<User> offlineUsers, Message message) {
        if (offlineUsers == null || offlineUsers.isEmpty()) return;
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            int count = 0;
            for (User u : offlineUsers) {
                MessageQueue item = new MessageQueue(u, message);
                session.persist(item);
                if (++count % 50 == 0) {
                    session.flush();
                    session.clear();
                }
            }
            tx.commit();
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to enqueue messages for offline users: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Query 1: Flush pending messages on reconnect.
     * Uses JOIN FETCH on message and sender to avoid LazyInitializationException.
     */
    public List<MessageDto> findUndeliveredForUser(Integer userId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            List<MessageQueue> queueItems = session.createQuery(
                    "select mq from MessageQueue mq " +
                    "join fetch mq.message m " +
                    "join fetch m.sender s " +
                    "left join fetch m.batch b " +
                    "where mq.user.userId = :uid and mq.delivered = false " +
                    "order by m.timestamp asc",
                    MessageQueue.class)
                    .setParameter("uid", userId)
                    .list();

            List<MessageDto> dtos = new ArrayList<>();
            for (MessageQueue item : queueItems) {
                dtos.add(MessageDao.toDto(item.getMessage()));
            }
            return dtos;
        }
    }

    /**
     * Query 5: Mark queue items delivered in a single bulk HQL update
     */
    public int markDeliveredForUser(Integer userId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            int updated = session.createMutationQuery(
                    "update MessageQueue mq set mq.delivered = true where mq.user.userId = :uid and mq.delivered = false")
                    .setParameter("uid", userId)
                    .executeUpdate();
            tx.commit();
            return updated;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to mark messages delivered for user {}: {}", userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Query 10: Count pending undelivered queue rows for admin dashboard
     */
    public long countPendingQueue() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Long count = session.createQuery(
                    "select count(mq) from MessageQueue mq where mq.delivered = false",
                    Long.class)
                    .uniqueResult();
            return count != null ? count : 0L;
        }
    }
}
