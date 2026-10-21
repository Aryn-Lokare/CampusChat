package com.campuschat;

import com.campuschat.dao.BatchDao;
import com.campuschat.dao.UserDao;
import com.campuschat.model.Batch;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import com.campuschat.util.HibernateUtil;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class TestDbConnection {

    private static final Logger logger = LoggerFactory.getLogger(TestDbConnection.class);

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("  Testing CampusChat MySQL Connection & Hibernate ");
        System.out.println("==================================================");

        try {
            SessionFactory sessionFactory = HibernateUtil.getSessionFactory();
            System.out.println("[SUCCESS] Hibernate SessionFactory initialized successfully!");

            BatchDao batchDao = new BatchDao();
            List<Batch> batches = batchDao.findAll();
            System.out.println("[INFO] Total Batches found in database: " + batches.size());

            System.out.println("[SUCCESS] Database & Hibernate entities verified successfully!");
        } catch (Throwable t) {
            System.err.println("[ERROR] Failed to connect or validate database:");
            t.printStackTrace(System.err);
            System.err.println("\nNote: Please verify that MySQL is running and credentials in src/main/resources/db.properties match your MySQL password.");
        } finally {
            HibernateUtil.shutdown();
        }
    }
}
