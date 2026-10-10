package com.campuschat.dao;

import com.campuschat.model.UserSession;
import com.campuschat.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

public class SessionDao {

    private static final Logger logger = LoggerFactory.getLogger(SessionDao.class);

    public UserSession save(UserSession userSession) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.persist(userSession);
            tx.commit();
            return userSession;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to save session: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Query 6: Validate session token on reconnect
     */
    public Optional<UserSession> findActiveByToken(String token) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery(
                    "from UserSession s join fetch s.user u left join fetch s.batch b " +
                    "where s.sessionToken = :token and s.active = true",
                    UserSession.class)
                    .setParameter("token", token)
                    .uniqueResultOptional();
        }
    }

    /**
     * Update last_seen_time on heartbeat or reconnect
     */
    public void touchSession(String token) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.createMutationQuery(
                    "update UserSession s set s.lastSeenTime = :now where s.sessionToken = :token")
                    .setParameter("now", Instant.now())
                    .setParameter("token", token)
                    .executeUpdate();
            tx.commit();
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.warn("Could not touch session: {}", e.getMessage());
        }
    }

    public void deactivateSession(String token) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.createMutationQuery(
                    "update UserSession s set s.active = false where s.sessionToken = :token")
                    .setParameter("token", token)
                    .executeUpdate();
            tx.commit();
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to deactivate session: {}", e.getMessage(), e);
        }
    }

    public void deactivateAllUserSessions(Integer userId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.createMutationQuery(
                    "update UserSession s set s.active = false where s.user.id = :uid")
                    .setParameter("uid", userId)
                    .executeUpdate();
            tx.commit();
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to deactivate user sessions: {}", e.getMessage(), e);
        }
    }

    /**
     * Query 10: Count active sessions for admin dashboard
     */
    public long countActiveSessions() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Long count = session.createQuery(
                    "select count(s) from UserSession s where s.active = true",
                    Long.class)
                    .uniqueResult();
            return count != null ? count : 0L;
        }
    }
}
