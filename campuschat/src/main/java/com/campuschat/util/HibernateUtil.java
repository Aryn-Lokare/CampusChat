package com.campuschat.util;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

/**
 * Thread-safe singleton manager for Hibernate SessionFactory.
 */
public final class HibernateUtil {

    private static final Logger logger = LoggerFactory.getLogger(HibernateUtil.class);
    private static SessionFactory sessionFactory;

    private HibernateUtil() {}

    public static synchronized SessionFactory getSessionFactory() {
        if (sessionFactory == null) {
            sessionFactory = buildSessionFactory("hibernate.cfg.xml");
        }
        return sessionFactory;
    }

    /**
     * Builds session factory using the specified config file.
     * Overrides DB credentials from db.properties if present.
     */
    public static SessionFactory buildSessionFactory(String configResource) {
        try {
            StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder()
                    .configure(configResource);

            // Dynamically override DB connection properties from db.properties if running production config
            if ("hibernate.cfg.xml".equals(configResource)) {
                try (InputStream propStream = HibernateUtil.class.getClassLoader().getResourceAsStream("db.properties")) {
                    if (propStream != null) {
                        Properties props = new Properties();
                        props.load(propStream);
                        if (props.containsKey("db.url")) {
                            registryBuilder.applySetting("hibernate.connection.url", props.getProperty("db.url"));
                            registryBuilder.applySetting("hibernate.hikari.jdbcUrl", props.getProperty("db.url"));
                        }
                        if (props.containsKey("db.user")) {
                            registryBuilder.applySetting("hibernate.connection.username", props.getProperty("db.user"));
                            registryBuilder.applySetting("hibernate.hikari.dataSource.user", props.getProperty("db.user"));
                        }
                        if (props.containsKey("db.password")) {
                            registryBuilder.applySetting("hibernate.connection.password", props.getProperty("db.password"));
                            registryBuilder.applySetting("hibernate.hikari.dataSource.password", props.getProperty("db.password"));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not read db.properties, falling back to hibernate.cfg.xml defaults", e);
                }
            }

            StandardServiceRegistry registry = registryBuilder.build();
            logger.info("Initializing Hibernate SessionFactory from {}", configResource);
            return new MetadataSources(registry).buildMetadata().buildSessionFactory();
        } catch (Throwable ex) {
            logger.error("Initial SessionFactory creation failed: {}", ex.getMessage(), ex);
            throw new ExceptionInInitializerError(ex);
        }
    }

    /**
     * Set a custom SessionFactory (e.g., for test environments).
     */
    public static synchronized void setSessionFactory(SessionFactory customFactory) {
        sessionFactory = customFactory;
    }

    public static synchronized void shutdown() {
        if (sessionFactory != null && !sessionFactory.isClosed()) {
            logger.info("Closing Hibernate SessionFactory");
            sessionFactory.close();
            sessionFactory = null;
        }
    }
}
