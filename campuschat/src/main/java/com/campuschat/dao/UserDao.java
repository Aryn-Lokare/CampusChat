package com.campuschat.dao;

import com.campuschat.dto.UserDto;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import com.campuschat.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class UserDao {

    private static final Logger logger = LoggerFactory.getLogger(UserDao.class);

    public User save(User user) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.persist(user);
            tx.commit();
            return user;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to save user: {}", e.getMessage(), e);
            throw e;
        }
    }

    public void updatePassword(Integer userId, String newHashedPassword) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.createMutationQuery("update User u set u.password = :pwd where u.userId = :id")
                    .setParameter("pwd", newHashedPassword)
                    .setParameter("id", userId)
                    .executeUpdate();
            tx.commit();
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to update password: {}", e.getMessage(), e);
        }
    }

    public Optional<User> findById(Integer userId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            User user = session.get(User.class, userId);
            return Optional.ofNullable(user);
        }
    }

    /**
     * Query 7: Login lookup by email or username
     */
    public Optional<User> findByUsernameOrEmail(String identifier) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery(
                    "from User u left join fetch u.batch where u.email = :id or u.username = :id",
                    User.class)
                    .setParameter("id", identifier)
                    .uniqueResultOptional();
        }
    }

    public Optional<User> findByUsername(String username) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery(
                    "from User u left join fetch u.batch where u.username = :uname",
                    User.class)
                    .setParameter("uname", username)
                    .uniqueResultOptional();
        }
    }

    /**
     * Query 8: Batch members for broadcast (active users in batch)
     */
    public List<User> findActiveUsersByBatchId(Integer batchId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery(
                    "from User u where u.batch.id = :bid and u.status = :status",
                    User.class)
                    .setParameter("bid", batchId)
                    .setParameter("status", UserStatus.ACTIVE)
                    .list();
        }
    }

    /**
     * Query 11: Ban, unban, or change status
     */
    public int updateStatus(String username, UserStatus status) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            int updated = session.createMutationQuery(
                    "update User u set u.status = :status where u.username = :uname")
                    .setParameter("status", status)
                    .setParameter("uname", username)
                    .executeUpdate();
            tx.commit();
            return updated;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to update user status: {}", e.getMessage(), e);
            throw e;
        }
    }

    public int updateRole(String username, Role role) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            int updated = session.createMutationQuery(
                    "update User u set u.role = :role where u.username = :uname")
                    .setParameter("role", role)
                    .setParameter("uname", username)
                    .executeUpdate();
            tx.commit();
            return updated;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to update user role: {}", e.getMessage(), e);
            throw e;
        }
    }

    public int updateBatch(String username, Integer batchId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            com.campuschat.model.Batch batch = batchId != null ? session.get(com.campuschat.model.Batch.class, batchId) : null;
            int updated = session.createMutationQuery(
                    "update User u set u.batch = :batch where u.username = :uname")
                    .setParameter("batch", batch)
                    .setParameter("uname", username)
                    .executeUpdate();
            tx.commit();
            return updated;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to update user batch: {}", e.getMessage(), e);
            throw e;
        }
    }

    public List<User> findAll() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery("from User u left join fetch u.batch order by u.username", User.class).list();
        }
    }

    public static UserDto toDto(User user) {
        if (user == null) return null;
        Integer batchId = user.getBatch() != null ? user.getBatch().getBatchId() : null;
        return new UserDto(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                batchId
        );
    }
}
