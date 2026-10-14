package com.campuschat;

import com.campuschat.client.loadtest.LoadTestClient;
import com.campuschat.dao.*;
import com.campuschat.model.*;
import com.campuschat.server.network.NioServer;
import com.campuschat.server.service.*;
import com.campuschat.server.state.BatchRoomRegistry;
import com.campuschat.server.state.SessionRegistry;
import com.campuschat.util.HibernateUtil;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Phase8AdminAndLoadTest {

    private static SessionFactory testSessionFactory;
    private static NioServer server;
    private static int serverPort;

    private static UserDao userDao;
    private static BatchDao batchDao;
    private static SessionDao sessionDao;
    private static MessageDao messageDao;
    private static QueueDao queueDao;
    private static SessionRegistry sessionRegistry;
    private static BatchRoomRegistry batchRoomRegistry;
    private static AdminService adminService;

    private static Batch testBatch;
    private static User testStudent;
    private static User testAdmin;

    @BeforeAll
    static void setUp() throws Exception {
        testSessionFactory = HibernateUtil.buildSessionFactory("hibernate-test.cfg.xml");
        HibernateUtil.setSessionFactory(testSessionFactory);

        batchDao = new BatchDao();
        userDao = new UserDao();
        sessionDao = new SessionDao();
        messageDao = new MessageDao();
        queueDao = new QueueDao();

        testBatch = batchDao.save(new Batch("IT-2028-AdminTest", 2028));

        String hashedPwd = AuthService.hashPassword("pass123");

        testStudent = new User("p8_student", "p8_student@college.edu", hashedPwd, Role.STUDENT, testBatch);
        testStudent.setStatus(UserStatus.ACTIVE);
        userDao.save(testStudent);

        testAdmin = new User("p8_admin", "p8_admin@college.edu", hashedPwd, Role.ADMIN, null);
        testAdmin.setStatus(UserStatus.ACTIVE);
        userDao.save(testAdmin);

        sessionRegistry = new SessionRegistry();
        batchRoomRegistry = new BatchRoomRegistry();

        AuthService authService = new AuthService(userDao);
        SessionService sessionService = new SessionService(sessionDao, sessionRegistry, batchRoomRegistry);
        MessageService messageService = new MessageService(messageDao, userDao, queueDao, sessionRegistry, batchRoomRegistry);
        RateLimiter rateLimiter = new RateLimiter();
        MockMailer mockMailer = new MockMailer();
        RegistrationService registrationService = new RegistrationService(userDao, batchDao, new VerificationDao(), mockMailer);
        adminService = new AdminService(userDao, batchDao, sessionDao, messageDao, queueDao, sessionRegistry, batchRoomRegistry);

        CommandRouter router = new CommandRouter(authService, sessionService, messageService,
                rateLimiter, registrationService, adminService);

        server = new NioServer(0, router, sessionRegistry, batchRoomRegistry);
        server.start();
        serverPort = server.getPort();
        assertTrue(serverPort > 0);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (testSessionFactory != null) {
            testSessionFactory.close();
        }
    }

    @Test
    @Order(1)
    void testNonAdminCannotExecuteAdminCommands() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            // Student logs in
            out.println("LOGIN|p8_student|pass123");
            String loginResp = in.readLine();
            assertNotNull(loginResp);
            assertTrue(loginResp.startsWith("OK|"));
            drainHistory(in);

            // Attempt ADMIN_STATS
            out.println("ADMIN_STATS");
            String statsResp = in.readLine();
            assertTrue(statsResp.startsWith("ERROR|Permission denied"), "Non-admin should be denied ADMIN_STATS");

            // Attempt ADMIN_CREATE_BATCH
            out.println("ADMIN_CREATE_BATCH|FakeBatch|2030");
            String batchResp = in.readLine();
            assertTrue(batchResp.startsWith("ERROR|Permission denied"), "Non-admin should be denied ADMIN_CREATE_BATCH");

            // Attempt ADMIN_SET_STATUS
            out.println("ADMIN_SET_STATUS|p8_student|BANNED");
            String banResp = in.readLine();
            assertTrue(banResp.startsWith("ERROR|Permission denied"), "Non-admin should be denied ADMIN_SET_STATUS");

            out.println("LOGOUT");
        }
    }

    @Test
    @Order(2)
    void testAdminStatsReporting() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            // Admin logs in
            out.println("LOGIN|p8_admin|pass123");
            assertTrue(in.readLine().startsWith("OK|"));

            // Request ADMIN_STATS
            out.println("ADMIN_STATS");
            String statsLine = in.readLine();
            assertNotNull(statsLine);
            assertTrue(statsLine.startsWith("STATS|"), "Admin should receive STATS response");

            // Format: STATS|onlineUsers|activeSessions|pendingQueue|messagesToday
            String[] parts = statsLine.split("\\|", 5);
            assertEquals(5, parts.length);
            assertTrue(Integer.parseInt(parts[1]) >= 1, "At least 1 online user (admin itself)");
            assertTrue(Long.parseLong(parts[2]) >= 1, "At least 1 active session");
            assertTrue(Long.parseLong(parts[3]) >= 0, "Valid pending queue count");
            assertTrue(Long.parseLong(parts[4]) >= 0, "Valid messages today count");

            out.println("LOGOUT");
        }
    }

    @Test
    @Order(3)
    void testAdminCreateBatchAndListing() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            out.println("LOGIN|p8_admin|pass123");
            assertTrue(in.readLine().startsWith("OK|"));

            // Create new batch
            String newBatchName = "Robotics-2029";
            out.println("ADMIN_CREATE_BATCH|" + newBatchName + "|2029");
            String createResp = in.readLine();
            assertNotNull(createResp);
            assertTrue(createResp.startsWith("OK|BATCH_CREATED|"), "Should succeed in creating batch");

            // Verify in DB
            Optional<Batch> createdBatch = batchDao.findByName(newBatchName);
            assertTrue(createdBatch.isPresent(), "Batch should be persisted in DB");
            assertEquals(2029, createdBatch.get().getYear());

            // List batches
            out.println("ADMIN_LIST_BATCHES");
            String startLine = in.readLine();
            assertTrue(startLine.startsWith("BATCHES_START|"));

            boolean found = false;
            String line;
            while ((line = in.readLine()) != null && !line.equals("BATCHES_END")) {
                if (line.contains(newBatchName)) {
                    found = true;
                }
            }
            assertTrue(found, "Newly created batch must appear in ADMIN_LIST_BATCHES");

            out.println("LOGOUT");
        }
    }

    @Test
    @Order(4)
    void testAdminSetRoleAndListing() throws Exception {
        String testUser = "p8_role_user_" + System.currentTimeMillis();
        User user = new User(testUser, testUser + "@college.edu", AuthService.hashPassword("pass123"), Role.STUDENT, testBatch);
        user.setStatus(UserStatus.ACTIVE);
        userDao.save(user);

        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            out.println("LOGIN|p8_admin|pass123");
            assertTrue(in.readLine().startsWith("OK|"));

            // Upgrade role to FACULTY
            out.println("ADMIN_SET_ROLE|" + testUser + "|FACULTY");
            String roleResp = in.readLine();
            assertNotNull(roleResp);
            assertTrue(roleResp.startsWith("OK|ROLE_UPDATED|" + testUser + "|FACULTY"));

            // Verify in DB
            User updated = userDao.findByUsername(testUser).orElseThrow();
            assertEquals(Role.FACULTY, updated.getRole(), "User role must be upgraded to FACULTY in DB");

            // List users
            out.println("ADMIN_LIST_USERS");
            assertTrue(in.readLine().startsWith("USERS_START|"));
            boolean userFound = false;
            String line;
            while ((line = in.readLine()) != null && !line.equals("USERS_END")) {
                if (line.contains(testUser) && line.contains("FACULTY")) {
                    userFound = true;
                }
            }
            assertTrue(userFound, "User with new role FACULTY must appear in user listing");

            out.println("LOGOUT");
        }
    }

    @Test
    @Order(5)
    void testAdminBanImmediatelyDropsLiveSocketAndBlocksLogin() throws Exception {
        String victimName = "p8_victim_" + System.currentTimeMillis();
        User victim = new User(victimName, victimName + "@college.edu", AuthService.hashPassword("pass123"), Role.STUDENT, testBatch);
        victim.setStatus(UserStatus.ACTIVE);
        userDao.save(victim);

        // 1. Victim connects and logs in
        Socket victimSock = new Socket("127.0.0.1", serverPort);
        BufferedReader victimIn = new BufferedReader(new InputStreamReader(victimSock.getInputStream()));
        PrintWriter victimOut = new PrintWriter(victimSock.getOutputStream(), true);

        victimOut.println("LOGIN|" + victimName + "|pass123");
        assertTrue(victimIn.readLine().startsWith("OK|"), "Victim should log in successfully");
        drainHistory(victimIn);

        // Confirm victim is registered online
        assertTrue(sessionRegistry.isUserOnline(victim.getUserId()), "Victim must be online in session registry");

        // 2. Admin logs in from another socket and bans victim (FR-11)
        try (Socket adminSock = new Socket("127.0.0.1", serverPort);
             BufferedReader adminIn = new BufferedReader(new InputStreamReader(adminSock.getInputStream()));
             PrintWriter adminOut = new PrintWriter(adminSock.getOutputStream(), true)) {

            adminOut.println("LOGIN|p8_admin|pass123");
            assertTrue(adminIn.readLine().startsWith("OK|"));

            // Issue ban command
            adminOut.println("ADMIN_SET_STATUS|" + victimName + "|BANNED");
            String banResp = adminIn.readLine();
            assertTrue(banResp.startsWith("OK|STATUS_UPDATED|" + victimName + "|BANNED"));
        }

        // 3. Verify victim receives error and their live connection is closed immediately
        String victimMsg = victimIn.readLine();
        assertNotNull(victimMsg, "Victim should receive notification before disconnect");
        assertTrue(victimMsg.contains("banned by administrator"), "Victim must receive ban error notice");

        // Next read should return null (EOF / socket closed by server)
        String eof = victimIn.readLine();
        assertNull(eof, "Live socket must be closed immediately upon being banned");
        victimSock.close();

        // Verify status in DB
        User bannedDbUser = userDao.findByUsername(victimName).orElseThrow();
        assertEquals(UserStatus.BANNED, bannedDbUser.getStatus(), "Status in DB must be BANNED");

        // 4. Banned user attempts to log back in -> must be rejected
        try (Socket retrySock = new Socket("127.0.0.1", serverPort);
             BufferedReader retryIn = new BufferedReader(new InputStreamReader(retrySock.getInputStream()));
             PrintWriter retryOut = new PrintWriter(retrySock.getOutputStream(), true)) {

            retryOut.println("LOGIN|" + victimName + "|pass123");
            String retryResp = retryIn.readLine();
            assertNotNull(retryResp);
            assertTrue(retryResp.contains("Account is banned"), "Login must fail for BANNED accounts");
        }

        // 5. Unban user -> login succeeds again
        adminService.setUserStatus(victimName, "ACTIVE");
        try (Socket activeSock = new Socket("127.0.0.1", serverPort);
             BufferedReader activeIn = new BufferedReader(new InputStreamReader(activeSock.getInputStream()));
             PrintWriter activeOut = new PrintWriter(activeSock.getOutputStream(), true)) {

            activeOut.println("LOGIN|" + victimName + "|pass123");
            assertTrue(activeIn.readLine().startsWith("OK|"), "User should be able to log in after being unbanned");
        }
    }

    @Test
    @Order(6)
    void testConcurrentLoadSimulation() throws Exception {
        // Run concurrent load test with 50 simultaneous connections against the test NIO server
        LoadTestClient loadTester = new LoadTestClient("127.0.0.1", serverPort, 50);
        LoadTestClient.LoadTestResult result = loadTester.run();

        assertEquals(50, result.connected(), "All 50 load connections should connect successfully");
        assertEquals(50, result.responses(), "All 50 responses should be received");
        assertTrue(result.avgMs() < 500, "Average latency should be low (< 500ms)");
    }

    private static void drainHistory(BufferedReader in) throws Exception {
        String header = in.readLine();
        if (header != null && header.startsWith("HISTORY_START|")) {
            String line;
            while ((line = in.readLine()) != null && !line.startsWith("HISTORY_END|")) {}
        }
    }
}
