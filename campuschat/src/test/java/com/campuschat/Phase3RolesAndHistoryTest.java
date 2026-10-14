package com.campuschat;

import com.campuschat.dao.BatchDao;
import com.campuschat.dao.MessageDao;
import com.campuschat.dao.SessionDao;
import com.campuschat.dao.UserDao;
import com.campuschat.model.Batch;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Phase3RolesAndHistoryTest {

    private static SessionFactory testSessionFactory;
    private static NioServer server;
    private static int serverPort;

    private static Batch batch1;
    private static Batch batch2;
    private static User studentBob;
    private static User facultySmith;
    private static User adminRoot;

    @BeforeAll
    static void setUp() throws Exception {
        testSessionFactory = HibernateUtil.buildSessionFactory("hibernate-test.cfg.xml");
        HibernateUtil.setSessionFactory(testSessionFactory);

        BatchDao batchDao = new BatchDao();
        UserDao userDao = new UserDao();
        SessionDao sessionDao = new SessionDao();
        MessageDao messageDao = new MessageDao();

        batch1 = batchDao.save(new Batch("IT-2026-A", 2026));
        batch2 = batchDao.save(new Batch("IT-2026-B", 2026));

        String hashedPwd = AuthService.hashPassword("pass123");

        // 1. Student
        studentBob = new User("bob_student", "bob@college.edu", hashedPwd, Role.STUDENT, batch1);
        studentBob.setStatus(UserStatus.ACTIVE);
        userDao.save(studentBob);

        // 2. Faculty
        facultySmith = new User("prof_smith", "smith@college.edu", hashedPwd, Role.FACULTY, batch1);
        facultySmith.setStatus(UserStatus.ACTIVE);
        userDao.save(facultySmith);

        // 3. Admin (no batch)
        adminRoot = new User("admin_super", "admin@college.edu", hashedPwd, Role.ADMIN, null);
        adminRoot.setStatus(UserStatus.ACTIVE);
        userDao.save(adminRoot);

        SessionRegistry sessionRegistry = new SessionRegistry();
        BatchRoomRegistry batchRoomRegistry = new BatchRoomRegistry();
        AuthService authService = new AuthService(userDao);
        SessionService sessionService = new SessionService(sessionDao, sessionRegistry, batchRoomRegistry);
        MessageService messageService = new MessageService(messageDao, userDao, batchRoomRegistry);
        CommandRouter router = new CommandRouter(authService, sessionService, messageService);

        // Bind to port 0 for ephemeral port allocation
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
        HibernateUtil.shutdown();
    }

    @Test
    @Order(1)
    void testStudentCannotAnnounceOrPostEvent() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            // Student logs in
            out.println("LOGIN|bob_student|pass123");
            String loginResp = in.readLine();
            assertNotNull(loginResp);
            assertTrue(loginResp.startsWith("OK|"));

            // Drain any initial history sent on login
            String historyHeader = in.readLine();
            if (historyHeader != null && historyHeader.startsWith("HISTORY_START|")) {
                String line;
                while ((line = in.readLine()) != null && !line.startsWith("HISTORY_END|")) {}
            }

            // Student attempts ANNOUNCE -> Must return ERROR and keep socket open (PRD FR-2, FR-5)
            out.println("ANNOUNCE|" + batch1.getBatchId() + "|Classes cancelled today!");
            String announceResp = in.readLine();
            assertNotNull(announceResp);
            assertTrue(announceResp.startsWith("ERROR|Permission denied"),
                    "Student ANNOUNCE must be rejected. Got: " + announceResp);

            // Student attempts EVENT -> Must return ERROR and keep socket open (PRD FR-2, FR-6)
            out.println("EVENT|" + batch1.getBatchId() + "|Hackathon|Tomorrow at 9 AM");
            String eventResp = in.readLine();
            assertNotNull(eventResp);
            assertTrue(eventResp.startsWith("ERROR|Permission denied"),
                    "Student EVENT must be rejected. Got: " + eventResp);

            // Student tries unauthorized batch -> Must return ERROR
            out.println("CHAT|" + batch2.getBatchId() + "|Hello foreign batch!");
            String batchResp = in.readLine();
            assertNotNull(batchResp);
            assertTrue(batchResp.startsWith("ERROR|Unauthorized batch room access"));

            // Connection still open, regular CHAT in own batch succeeds
            out.println("CHAT|" + batch1.getBatchId() + "|Hey everyone from Bob!");
            String chatEcho = in.readLine();
            assertNotNull(chatEcho);
            assertTrue(chatEcho.startsWith("MSG|CHAT|bob_student|" + batch1.getBatchId() + "|Hey everyone from Bob!|"));
        }
    }

    @Test
    @Order(2)
    void testFacultyCanPostAnnouncementAndEvent() throws Exception {
        try (Socket studentSock = new Socket("127.0.0.1", serverPort);
             BufferedReader inStudent = new BufferedReader(new InputStreamReader(studentSock.getInputStream()));
             PrintWriter outStudent = new PrintWriter(studentSock.getOutputStream(), true);

             Socket facultySock = new Socket("127.0.0.1", serverPort);
             BufferedReader inFaculty = new BufferedReader(new InputStreamReader(facultySock.getInputStream()));
             PrintWriter outFaculty = new PrintWriter(facultySock.getOutputStream(), true)) {

            // 1. Student connects and logs in
            outStudent.println("LOGIN|bob_student|pass123");
            assertTrue(inStudent.readLine().startsWith("OK|"));
            // Drain history
            drainHistory(inStudent);

            // 2. Faculty connects and logs in
            outFaculty.println("LOGIN|prof_smith|pass123");
            assertTrue(inFaculty.readLine().startsWith("OK|"));
            drainHistory(inFaculty);

            // 3. Faculty posts priority announcement
            outFaculty.println("ANNOUNCE|" + batch1.getBatchId() + "|Final Exam on Monday!");

            // Student receives ANNOUNCE broadcast
            String studentReceivedAnnounce = inStudent.readLine();
            assertNotNull(studentReceivedAnnounce);
            assertTrue(studentReceivedAnnounce.startsWith("MSG|ANNOUNCE|prof_smith|" + batch1.getBatchId() + "|Final Exam on Monday!|"),
                    "Student must receive ANNOUNCE broadcast. Got: " + studentReceivedAnnounce);

            // Faculty receives self-echo
            String facultyEchoAnnounce = inFaculty.readLine();
            assertNotNull(facultyEchoAnnounce);
            assertTrue(facultyEchoAnnounce.startsWith("MSG|ANNOUNCE|prof_smith|"));

            // 4. Faculty posts event notice
            outFaculty.println("EVENT|" + batch1.getBatchId() + "|Tech Symposium|2026-11-15 10:00 AM");

            // Student receives EVENT broadcast
            String studentReceivedEvent = inStudent.readLine();
            assertNotNull(studentReceivedEvent);
            assertTrue(studentReceivedEvent.startsWith("MSG|EVENT|prof_smith|" + batch1.getBatchId() + "|Tech Symposium | 2026-11-15 10:00 AM|"),
                    "Student must receive EVENT broadcast. Got: " + studentReceivedEvent);
        }
    }

    @Test
    @Order(3)
    void testAdminExcludedFromBatchChat() throws Exception {
        try (Socket adminSock = new Socket("127.0.0.1", serverPort);
             BufferedReader inAdmin = new BufferedReader(new InputStreamReader(adminSock.getInputStream()));
             PrintWriter outAdmin = new PrintWriter(adminSock.getOutputStream(), true)) {

            // Admin logs in
            outAdmin.println("LOGIN|admin_super|pass123");
            String loginResp = inAdmin.readLine();
            assertNotNull(loginResp);
            assertTrue(loginResp.startsWith("OK|"));

            // Admin attempting CHAT is rejected (PRD Section 4: Admin is not a chat participant)
            outAdmin.println("CHAT|" + batch1.getBatchId() + "|I am admin!");
            String chatResp = inAdmin.readLine();
            assertNotNull(chatResp);
            assertTrue(chatResp.startsWith("ERROR|Permission denied: Admins cannot participate in batch chat"),
                    "Admin CHAT must be rejected. Got: " + chatResp);

            // Admin attempting ANNOUNCE is also rejected
            outAdmin.println("ANNOUNCE|" + batch1.getBatchId() + "|Admin notice");
            String announceResp = inAdmin.readLine();
            assertNotNull(announceResp);
            assertTrue(announceResp.startsWith("ERROR|Permission denied"),
                    "Admin ANNOUNCE must be rejected. Got: " + announceResp);
        }
    }

    @Test
    @Order(4)
    void testChatHistoryRetrieval() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            out.println("LOGIN|bob_student|pass123");
            assertTrue(in.readLine().startsWith("OK|"));

            // Read the automatic history delivered upon room entry
            List<String> historyMessages = new ArrayList<>();
            String line = in.readLine();
            assertNotNull(line);
            assertTrue(line.startsWith("HISTORY_START|" + batch1.getBatchId()), "Expected HISTORY_START header. Got: " + line);

            while ((line = in.readLine()) != null) {
                if (line.startsWith("HISTORY_END|")) {
                    break;
                }
                historyMessages.add(line);
            }

            // We sent at least 3 messages to batch1 earlier (Bob's chat, Smith's announce, Smith's event)
            assertTrue(historyMessages.size() >= 3, "History should contain earlier persisted messages");

            // Also test manual HISTORY command
            out.println("HISTORY|" + batch1.getBatchId());
            String manualHeader = in.readLine();
            assertTrue(manualHeader.startsWith("HISTORY_START|" + batch1.getBatchId()));
            int manualCount = 0;
            while ((line = in.readLine()) != null && !line.startsWith("HISTORY_END|")) {
                manualCount++;
            }
            assertEquals(historyMessages.size(), manualCount);
        }
    }

    private static void drainHistory(BufferedReader in) throws Exception {
        String header = in.readLine();
        if (header != null && header.startsWith("HISTORY_START|")) {
            String line;
            while ((line = in.readLine()) != null && !line.startsWith("HISTORY_END|")) {}
        }
    }
}
