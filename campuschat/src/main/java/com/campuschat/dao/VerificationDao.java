package com.campuschat.dao;

import com.campuschat.model.EmailVerification;
import com.campuschat.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

public class VerificationDao {

    private static final Logger logger = LoggerFactory.getLogger(VerificationDao.class);

    public EmailVerification save(EmailVerification verification) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.persist(verification);
            tx.commit();
            return verification;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to save email verification: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Query 9: Verify email token
     */
    public Optional<EmailVerification> findValidByTokenHash(String tokenHash) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery(
                    "from EmailVerification v join fetch v.user u " +
                    "where v.tokenHash = :hash and v.used = false and v.expiresAt > :now",
                    EmailVerification.class)
                    .setParameter("hash", tokenHash)
                    .setParameter("now", Instant.now())
                    .uniqueResultOptional();
        }
    }

    /**
     * Mark token used after successful activation
     */
    public void markUsed(Integer verificationId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.createMutationQuery(
                    "update EmailVerification v set v.used = true where v.verificationId = :id")
                    .setParameter("id", verificationId)
                    .executeUpdate();
            tx.commit();
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to mark verification token used: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Count verification requests created in the last hour for abuse/rate limiting
     */
    public long countRecentRequestsForUser(Integer userId, Instant since) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Long count = session.createQuery(
                    "select count(v) from EmailVerification v " +
                    "where v.user.userId = :uid and v.expiresAt > :since",
                    Long.class)
                    .setParameter("uid", userId)
                    .setParameter("since", since)
                    .uniqueResult();
            return count != null ? count : 0L;
        }
    }
}
