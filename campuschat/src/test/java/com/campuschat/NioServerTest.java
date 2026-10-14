package com.campuschat;

import com.campuschat.dao.BatchDao;
import com.campuschat.dao.MessageDao;
import com.campuschat.dao.SessionDao;
import com.campuschat.dao.UserDao;
import com.campuschat.model.Batch;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import com.campuschat.server.network.LineBuffer;
import com.campuschat.server.network.NioServer;
import com.campuschat.server.service.AuthService;
import com.campuschat.server.service.CommandRouter;
import com.campuschat.server.service.MessageService;
import com.campuschat.server.service.SessionService;
import com.campuschat.server.state.BatchRoomRegistry;
import com.campuschat.server.state.SessionRegistry;
import com.campuschat.util.HibernateUtil;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NioServerTest {

    private static SessionFactory testSessionFactory;
    private static NioServer server;
    private static int serverPort;
    private static Batch testBatch;
    private static User userA;
    private static User userB;

    @BeforeAll
    static void setUp() throws Exception {
        testSessionFactory = HibernateUtil.buildSessionFactory("hibernate-test.cfg.xml");
        HibernateUtil.setSessionFactory(testSessionFactory);

        BatchDao batchDao = new BatchDao();
        UserDao userDao = new UserDao();
        SessionDao sessionDao = new SessionDao();
        MessageDao messageDao = new MessageDao();

        testBatch = batchDao.save(new Batch("IT-2026", 2026));

        String hashedPwd = AuthService.hashPassword("secret123");
        userA = new User("userA", "userA@college.edu", hashedPwd, Role.STUDENT, testBatch);
        userA.setStatus(UserStatus.ACTIVE);
        userDao.save(userA);

        userB = new User("userB", "userB@college.edu", hashedPwd, Role.STUDENT, testBatch);
        userB.setStatus(UserStatus.ACTIVE);
        userDao.save(userB);

        SessionRegistry sessionRegistry = new SessionRegistry();
        BatchRoomRegistry batchRoomRegistry = new BatchRoomRegistry();
        AuthService authService = new AuthService(userDao);
        SessionService sessionService = new SessionService(sessionDao, sessionRegistry, batchRoomRegistry);
        MessageService messageService = new MessageService(messageDao, userDao, batchRoomRegistry);
        CommandRouter router = new CommandRouter(authService, sessionService, messageService);

        // Bind to port 0 for dynamic ephemeral port allocation
        server = new NioServer(0, router, sessionRegistry, batchRoomRegistry);
        server.start();
        serverPort = server.getPort();
        assertTrue(serverPort > 0, "Server should be bound to a valid local port");
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
        HibernateUtil.shutdown();
    }

    @Test
    @Order(1)
    void testLineBufferFraming() throws Exception {
        LineBuffer buffer = new LineBuffer();

        // 1. Partial chunk followed by completion
        ByteBuffer chunk1 = ByteBuffer.wrap("LOGIN|ali".getBytes(StandardCharsets.UTF_8));
        List<String> lines1 = buffer.processBytes(chunk1);
        assertTrue(lines1.isEmpty(), "Partial line should not be emitted");

        ByteBuffer chunk2 = ByteBuffer.wrap("ce|secret\nCHAT|1|Hello\n".getBytes(StandardCharsets.UTF_8));
        List<String> lines2 = buffer.processBytes(chunk2);
        assertEquals(2, lines2.size());
        assertEquals("LOGIN|alice|secret", lines2.get(0));
        assertEquals("CHAT|1|Hello", lines2.get(1));

        // 2. Line exceeding 4 KB throws LineTooLongException
        LineBuffer overflowBuffer = new LineBuffer();
        byte[] largeData = new byte[4097];
        java.util.Arrays.fill(largeData, (byte) 'A');
        assertThrows(LineBuffer.LineTooLongException.class, () ->
                overflowBuffer.processBytes(ByteBuffer.wrap(largeData)));
    }

    @Test
    @Order(2)
    void testRealtimeChatBetweenClients() throws Exception {
        try (Socket sockA = new Socket("127.0.0.1", serverPort);
             BufferedReader inA = new BufferedReader(new InputStreamReader(sockA.getInputStream()));
             PrintWriter outA = new PrintWriter(sockA.getOutputStream(), true);

             Socket sockB = new Socket("127.0.0.1", serverPort);
             BufferedReader inB = new BufferedReader(new InputStreamReader(sockB.getInputStream()));
             PrintWriter outB = new PrintWriter(sockB.getOutputStream(), true)) {

            // 1. Client A logs in
            outA.println("LOGIN|userA|secret123");
            String respA = inA.readLine();
            assertNotNull(respA);
            assertTrue(respA.startsWith("OK|"), "Client A should successfully authenticate: " + respA);

            // 2. Client B logs in
            outB.println("LOGIN|userB|secret123");
            String respB = inB.readLine();
            assertNotNull(respB);
            assertTrue(respB.startsWith("OK|"), "Client B should successfully authenticate: " + respB);

            // 3. Client A broadcasts a message to their batch
            outA.println("CHAT|" + testBatch.getBatchId() + "|Hello from User A!");

            // 4. Client B must receive the broadcast message
            String broadcastMsg = inB.readLine();
            assertNotNull(broadcastMsg);
            assertTrue(broadcastMsg.startsWith("MSG|CHAT|userA|" + testBatch.getBatchId() + "|Hello from User A!|"),
                    "Client B must receive the broadcast message. Got: " + broadcastMsg);

            // 5. Client A also receives their own broadcast reflection
            String selfEcho = inA.readLine();
            assertNotNull(selfEcho);
            assertTrue(selfEcho.startsWith("MSG|CHAT|userA|"));

            // 6. Test malformed protocol command: returns ERROR and stays alive
            outA.println("UNKNOWN_CMD|testing");
            String errResp = inA.readLine();
            assertNotNull(errResp);
            assertTrue(errResp.startsWith("ERROR|"), "Unknown command should return ERROR: " + errResp);

            // Connection stays open; send logout
            outA.println("LOGOUT");
            String logoutResp = inA.readLine();
            assertNotNull(logoutResp);
            assertEquals("OK|LOGGED_OUT", logoutResp);
        }
    }
}
