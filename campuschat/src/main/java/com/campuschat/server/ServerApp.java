package com.campuschat.server;

import com.campuschat.dao.MessageDao;
import com.campuschat.dao.SessionDao;
import com.campuschat.dao.UserDao;
import com.campuschat.server.network.NioServer;
import com.campuschat.server.service.AuthService;
import com.campuschat.server.service.CommandRouter;
import com.campuschat.server.service.MessageService;
import com.campuschat.server.service.RateLimiter;
import com.campuschat.server.service.SessionService;
import com.campuschat.server.state.BatchRoomRegistry;
import com.campuschat.server.state.SessionRegistry;
import com.campuschat.util.HibernateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Server Application entry point for Campus Chat System.
 */
public class ServerApp {

    private static final Logger logger = LoggerFactory.getLogger(ServerApp.class);
    public static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.warn("Invalid port argument '{}', using default {}", args[0], DEFAULT_PORT);
            }
        }

        System.out.println("==================================================");
        System.out.println("        Campus Chat System - NIO Server           ");
        System.out.println("==================================================");

        try {
            // 1. Initialize Hibernate SessionFactory
            logger.info("Starting database connection pool...");
            HibernateUtil.getSessionFactory();

            // 2. Initialize Repositories
            UserDao userDao = new UserDao();
            SessionDao sessionDao = new SessionDao();
            MessageDao messageDao = new MessageDao();
            com.campuschat.dao.QueueDao queueDao = new com.campuschat.dao.QueueDao();
            com.campuschat.dao.BatchDao batchDao = new com.campuschat.dao.BatchDao();
            com.campuschat.dao.VerificationDao verificationDao = new com.campuschat.dao.VerificationDao();

            // 3. Initialize In-Memory State
            SessionRegistry sessionRegistry = new SessionRegistry();
            BatchRoomRegistry batchRoomRegistry = new BatchRoomRegistry();

            // 4. Initialize Services
            AuthService authService = new AuthService(userDao);
            SessionService sessionService = new SessionService(sessionDao, sessionRegistry, batchRoomRegistry);
            MessageService messageService = new MessageService(messageDao, userDao, queueDao, sessionRegistry, batchRoomRegistry);
            RateLimiter rateLimiter = new RateLimiter();
            com.campuschat.server.service.MockMailer mailService = new com.campuschat.server.service.MockMailer();
            com.campuschat.server.service.RegistrationService registrationService =
                    new com.campuschat.server.service.RegistrationService(userDao, batchDao, verificationDao, mailService);
            CommandRouter commandRouter = new CommandRouter(authService, sessionService, messageService, rateLimiter, registrationService);

            // 5. Start NIO Server
            NioServer server = new NioServer(port, commandRouter, sessionRegistry, batchRoomRegistry);
            server.start();

            System.out.println("[READY] Server is listening for TCP connections on port " + port);
            System.out.println("[INFO] Press Ctrl+C to stop the server.\n");

            // Register JVM shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[SHUTDOWN] Stopping server and closing connections...");
                rateLimiter.shutdown();
                server.stop();
                HibernateUtil.shutdown();
                System.out.println("[SHUTDOWN] Server stopped successfully.");
            }));

        } catch (IOException e) {
            System.err.println("[ERROR] Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Throwable t) {
            System.err.println("[ERROR] Fatal server initialization error: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }
}
