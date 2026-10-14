package com.campuschat;

import com.campuschat.dao.BatchDao;
import com.campuschat.dao.MessageDao;
import com.campuschat.dao.QueueDao;
import com.campuschat.dao.SessionDao;
import com.campuschat.dao.UserDao;
import com.campuschat.dto.MessageDto;
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
public class Phase4OfflineQueueTest {

    private static SessionFactory testSessionFactory;
    private static NioServer server;
    private static int serverPort;

    private static Batch batch1;
    private static User studentAlice;
    private static User studentBob;
    private static User facultySmith;

    private static UserDao userDao;
    private static SessionDao sessionDao;
    private static MessageDao messageDao;
    private static QueueDao queueDao;
    private static SessionRegistry sessionRegistry;

    @BeforeAll
    static void setUp() throws Exception {
        testSessionFactory = HibernateUtil.buildSessionFactory("hibernate-test.cfg.xml");
        HibernateUtil.setSessionFactory(testSessionFactory);

        BatchDao batchDao = new BatchDao();
        userDao = new UserDao();
        sessionDao = new SessionDao();
        messageDao = new MessageDao();
        queueDao = new QueueDao();

        batch1 = batchDao.save(new Batch("IT-2026-Reliability", 2026));

        String hashedPwd = AuthService.hashPassword("pass123");

        // 1. Student Alice
        studentAlice = new User("alice_p4", "alice.p4@college.edu", hashedPwd, Role.STUDENT, batch1);
        studentAlice.setStatus(UserStatus.ACTIVE);
        userDao.save(studentAlice);

        // 2. Student Bob
        studentBob = new User("bob_p4", "bob.p4@college.edu", hashedPwd, Role.STUDENT, batch1);
        studentBob.setStatus(UserStatus.ACTIVE);
        userDao.save(studentBob);

        // 3. Faculty Dr. Smith
        facultySmith = new User("smith_p4", "smith.p4@college.edu", hashedPwd, Role.FACULTY, batch1);
        facultySmith.setStatus(UserStatus.ACTIVE);
        userDao.save(facultySmith);

        sessionRegistry = new SessionRegistry();
        BatchRoomRegistry batchRoomRegistry = new BatchRoomRegistry();
        AuthService authService = new AuthService(userDao);
        SessionService sessionService = new SessionService(sessionDao, sessionRegistry, batchRoomRegistry);
        MessageService messageService = new MessageService(messageDao, userDao, queueDao, sessionRegistry, batchRoomRegistry);
        CommandRouter router = new CommandRouter(authService, sessionService, messageService);

        // Port 0 for ephemeral port
        server = new NioServer(0, router, sessionRegistry, batchRoomRegistry);
        server.start();
        serverPort = server.getPort();
        assertTrue(serverPort > 0, "Server should be bound to an ephemeral port");
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
    void testOfflineQueueAndSyncFlow() throws Exception {
        // 1. Student Alice connects and logs in
        Socket sockAlice = new Socket("127.0.0.1", serverPort);
        BufferedReader inAlice = new BufferedReader(new InputStreamReader(sockAlice.getInputStream()));
        PrintWriter outAlice = new PrintWriter(sockAlice.getOutputStream(), true);

        outAlice.println("LOGIN|alice_p4|pass123");
        String aliceLoginResp = inAlice.readLine();
        assertTrue(aliceLoginResp.startsWith("OK|"), "Alice login should succeed");
        // Drain any history markers
        drainHistory(inAlice);

        // 2. Student Bob connects and logs in
        Socket sockBob = new Socket("127.0.0.1", serverPort);
        BufferedReader inBob = new BufferedReader(new InputStreamReader(sockBob.getInputStream()));
        PrintWriter outBob = new PrintWriter(sockBob.getOutputStream(), true);

        outBob.println("LOGIN|bob_p4|pass123");
        String bobLoginResp = inBob.readLine();
        assertTrue(bobLoginResp.startsWith("OK|"), "Bob login should succeed");
        String bobSessionToken = bobLoginResp.substring(3).trim();
        assertFalse(bobSessionToken.isBlank(), "Bob should receive a valid session token");
        drainHistory(inBob);

        // Confirm both online
        assertTrue(sessionRegistry.isUserOnline(studentAlice.getUserId()), "Alice should be online");
        assertTrue(sessionRegistry.isUserOnline(studentBob.getUserId()), "Bob should be online");

        // 3. Bob disconnects (simulating connection loss or app closing)
        sockBob.close();
        // Give server selector thread a brief moment to process EOF and unregister Bob
        Thread.sleep(300);
        assertFalse(sessionRegistry.isUserOnline(studentBob.getUserId()), "Bob should now be offline");

        // 4. Alice sends a chat message while Bob is offline
        outAlice.println("CHAT|" + batch1.getBatchId() + "|Hello Bob, are you there?");
        String aliceEcho = inAlice.readLine();
        assertNotNull(aliceEcho);
        assertTrue(aliceEcho.startsWith("MSG|CHAT|alice_p4|"), "Alice should receive her own broadcast");

        // 5. Faculty Dr. Smith connects and posts announcement & event notice while Bob is offline
        Socket sockSmith = new Socket("127.0.0.1", serverPort);
        BufferedReader inSmith = new BufferedReader(new InputStreamReader(sockSmith.getInputStream()));
        PrintWriter outSmith = new PrintWriter(sockSmith.getOutputStream(), true);

        outSmith.println("LOGIN|smith_p4|pass123");
        assertTrue(inSmith.readLine().startsWith("OK|"));
        drainHistory(inSmith);

        outSmith.println("ANNOUNCE|" + batch1.getBatchId() + "|Exam date rescheduled to next Monday");
        String announceEcho = inSmith.readLine();
        assertTrue(announceEcho.startsWith("MSG|ANNOUNCE|smith_p4|"));

        outSmith.println("EVENT|" + batch1.getBatchId() + "|Guest Lecture on AI|2026-11-15 10:00 AM");
        String eventEcho = inSmith.readLine();
        assertTrue(eventEcho.startsWith("MSG|EVENT|smith_p4|"));

        // Alice receives both the announcement and the event notice in real time
        String aliceAnnounce = inAlice.readLine();
        assertTrue(aliceAnnounce.startsWith("MSG|ANNOUNCE|smith_p4|"));
        String aliceEvent = inAlice.readLine();
        assertTrue(aliceEvent.startsWith("MSG|EVENT|smith_p4|"));

        // 6. Verify Bob has exactly 3 pending undelivered messages queued in the database
        List<MessageDto> pendingForBob = queueDao.findUndeliveredForUser(studentBob.getUserId());
        assertEquals(3, pendingForBob.size(), "Bob should have exactly 3 undelivered messages queued");
        assertEquals("CHAT", pendingForBob.get(0).type().name());
        assertEquals("Hello Bob, are you there?", pendingForBob.get(0).content());
        assertEquals("ANNOUNCE", pendingForBob.get(1).type().name());
        assertEquals("Exam date rescheduled to next Monday", pendingForBob.get(1).content());
        assertEquals("EVENT", pendingForBob.get(2).type().name());
        assertTrue(pendingForBob.get(2).content().contains("Guest Lecture on AI"));

        // 7. Bob reconnects using a brand new socket and his saved sessionToken (FR-8)
        Socket sockBobReconnect = new Socket("127.0.0.1", serverPort);
        BufferedReader inBobRec = new BufferedReader(new InputStreamReader(sockBobReconnect.getInputStream()));
        PrintWriter outBobRec = new PrintWriter(sockBobReconnect.getOutputStream(), true);

        outBobRec.println("RECONNECT|" + bobSessionToken);

        // Read OK|RECONNECTED
        String recResp = inBobRec.readLine();
        assertEquals("OK|RECONNECTED", recResp, "Server should confirm reconnection");

        // Read SYNC_START
        String syncStart = inBobRec.readLine();
        assertEquals("SYNC_START", syncStart, "Catch-up sync must start with SYNC_START");

        // Read the 3 pending messages in chronological order
        String synced1 = inBobRec.readLine();
        assertTrue(synced1.startsWith("MSG|CHAT|alice_p4|"), "First synced message should be Alice's chat");
        assertTrue(synced1.contains("Hello Bob, are you there?"));

        String synced2 = inBobRec.readLine();
        assertTrue(synced2.startsWith("MSG|ANNOUNCE|smith_p4|"), "Second synced message should be Faculty announcement");
        assertTrue(synced2.contains("Exam date rescheduled"));

        String synced3 = inBobRec.readLine();
        assertTrue(synced3.startsWith("MSG|EVENT|smith_p4|"), "Third synced message should be Faculty event");
        assertTrue(synced3.contains("Guest Lecture on AI"));

        // Read SYNC_END
        String syncEnd = inBobRec.readLine();
        assertEquals("SYNC_END", syncEnd, "Catch-up sync must terminate with SYNC_END");

        // 8. Verify all queue rows are now marked delivered in the database
        List<MessageDto> pendingAfterSync = queueDao.findUndeliveredForUser(studentBob.getUserId());
        assertTrue(pendingAfterSync.isEmpty(), "All pending queue messages should be marked delivered after sync");

        // 9. Bob is back online and can receive subsequent broadcasts
        outAlice.println("CHAT|" + batch1.getBatchId() + "|Glad you are back Bob!");
        inAlice.readLine(); // Alice echo
        String bobReceivedLive = inBobRec.readLine();
        assertTrue(bobReceivedLive.startsWith("MSG|CHAT|alice_p4|"), "Bob should receive live chat after reconnect");
        assertTrue(bobReceivedLive.contains("Glad you are back Bob!"));

        // Clean up sockets
        sockAlice.close();
        sockSmith.close();
        sockBobReconnect.close();
    }

    @Test
    @Order(2)
    void testReconnectInvalidToken() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            out.println("RECONNECT|non-existent-token-12345");
            String resp = in.readLine();
            assertTrue(resp.startsWith("ERROR|"), "Invalid token reconnect must return ERROR");
            assertTrue(resp.contains("Invalid or expired session token"));
        }
    }

    @Test
    @Order(3)
    void testReconnectWithNoPendingMessages() throws Exception {
        // Alice connects, logs in, disconnects, reconnects immediately without any offline messages
        Socket sock = new Socket("127.0.0.1", serverPort);
        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
        PrintWriter out = new PrintWriter(sock.getOutputStream(), true);

        out.println("LOGIN|alice_p4|pass123");
        String loginResp = in.readLine();
        String token = loginResp.substring(3).trim();
        drainHistory(in);
        sock.close();

        Thread.sleep(200);

        // Reconnect
        Socket sockRec = new Socket("127.0.0.1", serverPort);
        BufferedReader inRec = new BufferedReader(new InputStreamReader(sockRec.getInputStream()));
        PrintWriter outRec = new PrintWriter(sockRec.getOutputStream(), true);

        outRec.println("RECONNECT|" + token);
        assertEquals("OK|RECONNECTED", inRec.readLine());
        assertEquals("SYNC_START", inRec.readLine());
        assertEquals("SYNC_END", inRec.readLine());

        sockRec.close();
    }

    private static void drainHistory(BufferedReader in) throws Exception {
        // If HISTORY_START is sent, read until HISTORY_END
        in.mark(4096);
        String line = in.readLine();
        if (line != null && line.startsWith("HISTORY_START|")) {
            while ((line = in.readLine()) != null) {
                if (line.startsWith("HISTORY_END|")) {
                    break;
                }
            }
        } else {
            in.reset();
        }
    }
}
