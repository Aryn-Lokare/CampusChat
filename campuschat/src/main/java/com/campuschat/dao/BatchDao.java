package com.campuschat.dao;

import com.campuschat.dto.BatchDto;
import com.campuschat.model.Batch;
import com.campuschat.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class BatchDao {

    private static final Logger logger = LoggerFactory.getLogger(BatchDao.class);

    public Batch save(Batch batch) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.persist(batch);
            tx.commit();
            return batch;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to save batch: {}", e.getMessage(), e);
            throw e;
        }
    }

    public Optional<Batch> findById(Integer batchId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Batch batch = session.get(Batch.class, batchId);
            return Optional.ofNullable(batch);
        }
    }

    public Optional<Batch> findByName(String name) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery("from Batch b where b.name = :name", Batch.class)
                    .setParameter("name", name)
                    .uniqueResultOptional();
        }
    }

    public List<Batch> findAll() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.createQuery("from Batch b order by b.year desc, b.name", Batch.class).list();
        }
    }

    public boolean deleteById(int batchId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            Batch batch = session.get(Batch.class, batchId);
            if (batch == null) {
                return false;
            }

            // 1. Unassign users belonging to this batch
            session.createMutationQuery("update User u set u.batch = null where u.batch.id = :bid")
                    .setParameter("bid", batchId)
                    .executeUpdate();

            // 2. Clear sessions scoped to this batch
            session.createMutationQuery("delete from UserSession s where s.batch.id = :bid")
                    .setParameter("bid", batchId)
                    .executeUpdate();

            // 3. Delete queued messages and messages in this batch
            session.createMutationQuery("delete from MessageQueue mq where mq.message.id in (select m.messageId from Message m where m.batch.id = :bid)")
                    .setParameter("bid", batchId)
                    .executeUpdate();

            session.createMutationQuery("delete from Message m where m.batch.id = :bid")
                    .setParameter("bid", batchId)
                    .executeUpdate();

            // 4. Delete the batch
            session.remove(batch);

            tx.commit();
            logger.info("Batch #{} deleted successfully from database", batchId);
            return true;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) tx.rollback();
            logger.error("Failed to delete batch #{}: {}", batchId, e.getMessage(), e);
            throw e;
        }
    }

    public static BatchDto toDto(Batch batch) {
        if (batch == null) return null;
        return new BatchDto(
                batch.getBatchId(),
                batch.getName(),
                batch.getYear(),
                batch.getCreatedAt()
        );
    }
}
