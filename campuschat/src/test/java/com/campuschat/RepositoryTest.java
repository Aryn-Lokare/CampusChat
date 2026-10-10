package com.campuschat;

import com.campuschat.dao.*;
import com.campuschat.dto.MessageDto;
import com.campuschat.model.*;
import com.campuschat.util.HibernateUtil;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RepositoryTest {

    private static SessionFactory testSessionFactory;
    private static BatchDao batchDao;
    private static UserDao userDao;
    private static SessionDao sessionDao;
    private static MessageDao messageDao;
    private static QueueDao queueDao;
    private static VerificationDao verificationDao;

    private static Batch testBatch;
    private static User studentUser;
    private static User facultyUser;

    @BeforeAll
    static void setUp() {
        testSessionFactory = HibernateUtil.buildSessionFactory("hibernate-test.cfg.xml");
        HibernateUtil.setSessionFactory(testSessionFactory);

        batchDao = new BatchDao();
        userDao = new UserDao();
        sessionDao = new SessionDao();
        messageDao = new MessageDao();
        queueDao = new QueueDao();
        verificationDao = new VerificationDao();
    }

    @AfterAll
    static void tearDown() {
        HibernateUtil.shutdown();
    }

    @Test
    @Order(1)
    void testCreateBatch() {
        Batch batch = new Batch("Computer Engineering 2026", 2026);
        testBatch = batchDao.save(batch);

        assertNotNull(testBatch.getBatchId());
        assertEquals("Computer Engineering 2026", testBatch.getName());
        assertEquals(2026, testBatch.getYear());
    }

    @Test
    @Order(2)
    void testCreateUsers() {
        User student = new User("alice", "alice@college.edu", "hashed_pwd_alice", Role.STUDENT, testBatch);
        student.setStatus(UserStatus.ACTIVE);
        studentUser = userDao.save(student);

        User faculty = new User("prof_smith", "smith@college.edu", "hashed_pwd_smith", Role.FACULTY, testBatch);
        faculty.setStatus(UserStatus.ACTIVE);
        facultyUser = userDao.save(faculty);

        assertNotNull(studentUser.getUserId());
        assertNotNull(facultyUser.getUserId());

        Optional<User> loaded = userDao.findByUsernameOrEmail("alice@college.edu");
        assertTrue(loaded.isPresent());
        assertEquals("alice", loaded.get().getUsername());
        assertEquals(Role.STUDENT, loaded.get().getRole());
    }

    @Test
    @Order(3)
    void testUserSessionAndOptimisticLocking() {
        String token = "test-token-uuid-12345";
        UserSession session = new UserSession(token, studentUser, testBatch, "Desktop App");
        UserSession saved = sessionDao.save(session);

        assertNotNull(saved.getSessionId());
        assertEquals(0, saved.getVersion());

        Optional<UserSession> found = sessionDao.findActiveByToken(token);
        assertTrue(found.isPresent());
        assertEquals(studentUser.getUserId(), found.get().getUser().getUserId());

        // Test touch session
        sessionDao.touchSession(token);
    }

    @Test
    @Order(4)
    void testMessagePersistenceAndTypes() {
        Message chat = new Message(studentUser, testBatch, "Hello class!", MessageType.CHAT);
        Message savedChat = messageDao.save(chat);
        assertNotNull(savedChat.getMessageId());
        assertEquals(MessageType.CHAT, savedChat.getType());

        Message announce = new Message(facultyUser, testBatch, "Exam rescheduled to Friday", MessageType.ANNOUNCE);
        Message savedAnnounce = messageDao.save(announce);
        assertNotNull(savedAnnounce.getMessageId());
        assertEquals(MessageType.ANNOUNCE, savedAnnounce.getType());

        // Test history query (last 50 messages)
        List<MessageDto> history = messageDao.findRecentHistory(testBatch.getBatchId(), 50);
        assertEquals(2, history.size());
        assertEquals("Hello class!", history.get(0).content());
        assertEquals("Exam rescheduled to Friday", history.get(1).content());
    }

    @Test
    @Order(5)
    void testOfflineQueueAndSyncReconnect() {
        // Assume studentUser is offline when faculty posts a priority announcement
        Message priorityNotice = new Message(facultyUser, testBatch, "Lab cancelled tomorrow", MessageType.ANNOUNCE);
        messageDao.save(priorityNotice);

        // Queue message for offline student
        queueDao.enqueueForUsers(List.of(studentUser), priorityNotice);

        // Query pending messages for student
        List<MessageDto> pending = queueDao.findUndeliveredForUser(studentUser.getUserId());
        assertEquals(1, pending.size());
        assertEquals("Lab cancelled tomorrow", pending.get(0).content());

        // Mark delivered on reconnect sync
        int markedCount = queueDao.markDeliveredForUser(studentUser.getUserId());
        assertEquals(1, markedCount);

        // Now pending should be empty
        List<MessageDto> afterSync = queueDao.findUndeliveredForUser(studentUser.getUserId());
        assertTrue(afterSync.isEmpty());
    }

    @Test
    @Order(6)
    void testEmailVerificationToken() {
        String tokenHash = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8";
        Instant expires = Instant.now().plus(15, ChronoUnit.MINUTES);

        EmailVerification verification = new EmailVerification(studentUser, tokenHash, expires);
        verificationDao.save(verification);

        Optional<EmailVerification> found = verificationDao.findValidByTokenHash(tokenHash);
        assertTrue(found.isPresent());
        assertFalse(found.get().getUsed());

        verificationDao.markUsed(found.get().getVerificationId());
        Optional<EmailVerification> reused = verificationDao.findValidByTokenHash(tokenHash);
        assertFalse(reused.isPresent(), "Used verification token should not be returned as valid");
    }

    @Test
    @Order(7)
    void testAdminQueries() {
        long activeSessions = sessionDao.countActiveSessions();
        assertTrue(activeSessions >= 1);

        long pendingQueue = queueDao.countPendingQueue();
        assertEquals(0, pendingQueue); // Was marked delivered earlier

        long messagesToday = messageDao.countMessagesSince(Instant.now().minus(1, ChronoUnit.DAYS));
        assertTrue(messagesToday >= 3);
    }
}
