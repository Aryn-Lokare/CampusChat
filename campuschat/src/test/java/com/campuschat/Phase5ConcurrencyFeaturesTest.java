package com.campuschat;

import com.campuschat.dao.BatchDao;
import com.campuschat.dao.MessageDao;
import com.campuschat.dao.QueueDao;
import com.campuschat.dao.SessionDao;
import com.campuschat.dao.UserDao;
import com.campuschat.model.Batch;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import com.campuschat.server.network.ConnectionContext;
import com.campuschat.server.network.NioServer;
import com.campuschat.server.network.OutboundMessage;
import com.campuschat.server.service.AuthService;
import com.campuschat.server.service.CommandRouter;
import com.campuschat.server.service.MessageService;
import com.campuschat.server.service.RateLimiter;
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
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Phase5ConcurrencyFeaturesTest {

    private static SessionFactory testSessionFactory;
    private static NioServer server;
    private static int serverPort;
    private static RateLimiter rateLimiter;

    private static Batch batch1;
    private static User studentCharlie;
    private static User facultyMiller;

    @BeforeAll
    static void setUp() throws Exception {
        testSessionFactory = HibernateUtil.buildSessionFactory("hibernate-test.cfg.xml");
        HibernateUtil.setSessionFactory(testSessionFactory);

        BatchDao batchDao = new BatchDao();
        UserDao userDao = new UserDao();
        SessionDao sessionDao = new SessionDao();
        MessageDao messageDao = new MessageDao();
        QueueDao queueDao = new QueueDao();

        batch1 = batchDao.save(new Batch("IT-2026-Concurrency", 2026));

        String hashedPwd = AuthService.hashPassword("pass123");

        // 1. Student Charlie
        studentCharlie = new User("charlie_p5", "charlie.p5@college.edu", hashedPwd, Role.STUDENT, batch1);
        studentCharlie.setStatus(UserStatus.ACTIVE);
        userDao.save(studentCharlie);

        // 2. Faculty Dr. Miller
        facultyMiller = new User("miller_p5", "miller.p5@college.edu", hashedPwd, Role.FACULTY, batch1);
        facultyMiller.setStatus(UserStatus.ACTIVE);
        userDao.save(facultyMiller);

        SessionRegistry sessionRegistry = new SessionRegistry();
        BatchRoomRegistry batchRoomRegistry = new BatchRoomRegistry();
        AuthService authService = new AuthService(userDao);
        SessionService sessionService = new SessionService(sessionDao, sessionRegistry, batchRoomRegistry);
        MessageService messageService = new MessageService(messageDao, userDao, queueDao, sessionRegistry, batchRoomRegistry);

        // FR-10: Token bucket with capacity 3, refill 1 token every 1200ms
        rateLimiter = new RateLimiter(3, 1, 1200, TimeUnit.MILLISECONDS);
        CommandRouter router = new CommandRouter(authService, sessionService, messageService, rateLimiter);

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
        if (rateLimiter != null) {
            rateLimiter.shutdown();
        }
        HibernateUtil.shutdown();
    }

    @Test
    @Order(1)
    void testPriorityQueueOrdering() {
        // FR-9: Verify ANNOUNCE ranks above EVENT above CHAT, and timestamp/sequence breaks ties
        PriorityBlockingQueue<OutboundMessage> queue = new PriorityBlockingQueue<>();
        AtomicLong seq = new AtomicLong(0);

        // Enqueue mixed types in arbitrary order
        queue.add(OutboundMessage.of("MSG|CHAT|user1|1|Chat A|2026-10-14T10:00:00Z", seq.incrementAndGet()));
        queue.add(OutboundMessage.of("MSG|CHAT|user2|1|Chat B|2026-10-14T10:00:01Z", seq.incrementAndGet()));
        queue.add(OutboundMessage.of("MSG|EVENT|prof|1|Midterm Event|2026-10-14T10:00:02Z", seq.incrementAndGet()));
        queue.add(OutboundMessage.of("MSG|ANNOUNCE|prof|1|Emergency Announcement!|2026-10-14T10:00:03Z", seq.incrementAndGet()));
        queue.add(OutboundMessage.of("MSG|CHAT|user3|1|Chat C|2026-10-14T10:00:04Z", seq.incrementAndGet()));
        queue.add(OutboundMessage.of("SYNC_END", seq.incrementAndGet()));
        queue.add(OutboundMessage.of("OK|session-token-xyz", seq.incrementAndGet()));

        // Polling from priority queue must yield:
        // 1. Control (OK|...) - Priority 0
        OutboundMessage msg1 = queue.poll();
        assertNotNull(msg1);
        assertEquals(OutboundMessage.PRIORITY_HIGH_CONTROL, msg1.getPriority());
        assertTrue(msg1.getRawMessage().startsWith("OK|"));

        // 2. Announcement (MSG|ANNOUNCE|...) - Priority 1 (Jumps ahead of earlier queued chats and events!)
        OutboundMessage msg2 = queue.poll();
        assertNotNull(msg2);
        assertEquals(OutboundMessage.PRIORITY_ANNOUNCE, msg2.getPriority());
        assertTrue(msg2.getRawMessage().contains("Emergency Announcement!"));

        // 3. Event Notice (MSG|EVENT|...) - Priority 2
        OutboundMessage msg3 = queue.poll();
        assertNotNull(msg3);
        assertEquals(OutboundMessage.PRIORITY_EVENT, msg3.getPriority());
        assertTrue(msg3.getRawMessage().contains("Midterm Event"));

        // 4, 5, 6. Chat Messages (MSG|CHAT|...) - Priority 3 (Preserved in sequence FIFO order)
        OutboundMessage msg4 = queue.poll();
        assertNotNull(msg4);
        assertEquals(OutboundMessage.PRIORITY_CHAT, msg4.getPriority());
        assertTrue(msg4.getRawMessage().contains("Chat A"));

        OutboundMessage msg5 = queue.poll();
        assertNotNull(msg5);
        assertEquals(OutboundMessage.PRIORITY_CHAT, msg5.getPriority());
        assertTrue(msg5.getRawMessage().contains("Chat B"));

        OutboundMessage msg6 = queue.poll();
        assertNotNull(msg6);
        assertEquals(OutboundMessage.PRIORITY_CHAT, msg6.getPriority());
        assertTrue(msg6.getRawMessage().contains("Chat C"));

        // 7. Low Control (SYNC_END) - Priority 4
        OutboundMessage msg7 = queue.poll();
        assertNotNull(msg7);
        assertEquals(OutboundMessage.PRIORITY_LOW_CONTROL, msg7.getPriority());
        assertEquals("SYNC_END", msg7.getRawMessage());

        assertTrue(queue.isEmpty(), "Queue should now be empty");
    }

    @Test
    @Order(2)
    void testStudentRateLimitingAndRefill() throws Exception {
        // FR-10: Student has capacity = 3. 4th message should be throttled.
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            out.println("LOGIN|charlie_p5|pass123");
            String loginResp = in.readLine();
            assertTrue(loginResp.startsWith("OK|"));
            drainHistory(in);

            // Send burst: first 3 consume all tokens, 4th and 5th must be throttled
            out.println("CHAT|" + batch1.getBatchId() + "|Msg 1");
            out.println("CHAT|" + batch1.getBatchId() + "|Msg 2");
            out.println("CHAT|" + batch1.getBatchId() + "|Msg 3");
            out.println("CHAT|" + batch1.getBatchId() + "|Msg 4 (Burst)");
            out.println("CHAT|" + batch1.getBatchId() + "|Msg 5 (Burst)");

            // Collect the 5 responses
            List<String> responses = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                responses.add(in.readLine());
            }

            // High-priority control messages (ERROR: Priority 0) jump ahead of CHAT (Priority 3) in PriorityBlockingQueue
            assertTrue(responses.get(0).startsWith("ERROR|Rate limit exceeded"),
                    "High-priority error should arrive first: " + responses.get(0));
            assertTrue(responses.get(1).startsWith("ERROR|Rate limit exceeded"),
                    "High-priority error should arrive second: " + responses.get(1));

            // The remaining 3 are the successful chat broadcasts
            assertTrue(responses.get(2).startsWith("MSG|CHAT|charlie_p5|"), "Echo 1 should succeed: " + responses.get(2));
            assertTrue(responses.get(3).startsWith("MSG|CHAT|charlie_p5|"), "Echo 2 should succeed: " + responses.get(3));
            assertTrue(responses.get(4).startsWith("MSG|CHAT|charlie_p5|"), "Echo 3 should succeed: " + responses.get(4));

            // Wait 1400ms for ScheduledExecutorService to refill tokens
            Thread.sleep(1400);

            // Send 6th message -> should succeed now that bucket has refilled
            out.println("CHAT|" + batch1.getBatchId() + "|Msg 6 (After Refill)");
            String echo6 = in.readLine();
            assertNotNull(echo6);
            assertTrue(echo6.startsWith("MSG|CHAT|charlie_p5|"),
                    "Message after token refill should succeed. Got: " + echo6);
        }
    }

    @Test
    @Order(3)
    void testFacultyExemptFromRateLimiting() throws Exception {
        // FR-10: Faculty members are explicitly exempt from rate limits
        try (Socket sock = new Socket("127.0.0.1", serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            out.println("LOGIN|miller_p5|pass123");
            String loginResp = in.readLine();
            assertTrue(loginResp.startsWith("OK|"));
            drainHistory(in);

            // Faculty sends 6 rapid messages (capacity is 3 for students, but faculty should not be blocked)
            for (int i = 1; i <= 6; i++) {
                out.println("ANNOUNCE|" + batch1.getBatchId() + "|Faculty Announcement #" + i);
            }

            // All 6 should be successfully delivered with NO rate limit errors
            for (int i = 1; i <= 6; i++) {
                String resp = in.readLine();
                assertNotNull(resp);
                assertFalse(resp.startsWith("ERROR|Rate limit exceeded"),
                        "Faculty should never be rate limited. Received error: " + resp);
                assertTrue(resp.startsWith("MSG|ANNOUNCE|miller_p5|"),
                        "Faculty should receive broadcast echo #" + i);
            }
        }
    }

    private static void drainHistory(BufferedReader in) throws Exception {
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
