package com.campuschat;

import com.campuschat.dao.BatchDao;
import com.campuschat.dao.UserDao;
import com.campuschat.dao.VerificationDao;
import com.campuschat.model.Batch;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import com.campuschat.server.network.ConnectionContext;
import com.campuschat.server.service.*;
import com.campuschat.util.HibernateUtil;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class Phase6RegistrationTest {

    private static SessionFactory testSessionFactory;
    private UserDao userDao;
    private BatchDao batchDao;
    private VerificationDao verificationDao;
    private MockMailer mockMailer;
    private RegistrationService registrationService;
    private AuthService authService;
    private CommandRouter commandRouter;
    private Integer testBatchId;

    static class DummyContext extends ConnectionContext {
        final List<String> sent = new ArrayList<>();

        DummyContext() throws IOException {
            super(SocketChannel.open());
        }

        @Override
        public void send(String msg) {
            sent.add(msg);
        }
    }

    @BeforeAll
    static void initDb() {
        testSessionFactory = HibernateUtil.buildSessionFactory("hibernate-test.cfg.xml");
        HibernateUtil.setSessionFactory(testSessionFactory);
    }

    @AfterAll
    static void tearDownDb() {
        if (testSessionFactory != null) {
            testSessionFactory.close();
        }
    }

    @BeforeEach
    void setUp() {
        userDao = new UserDao();
        batchDao = new BatchDao();
        verificationDao = new VerificationDao();
        mockMailer = new MockMailer();
        registrationService = new RegistrationService(userDao, batchDao, verificationDao, mockMailer);
        authService = new AuthService(userDao);

        commandRouter = new CommandRouter(authService, null, null, null, registrationService);

        // Ensure a test batch exists
        Optional<Batch> batchOpt = batchDao.findByName("Phase6Batch");
        if (batchOpt.isPresent()) {
            testBatchId = batchOpt.get().getBatchId();
        } else {
            Batch batch = new Batch();
            batch.setName("Phase6Batch");
            batch.setYear(2026);
            batchDao.save(batch);
            testBatchId = batch.getBatchId();
        }
    }

    @AfterEach
    void tearDown() {
        mockMailer.clear();
    }

    @Test
    void testInstitutionalDomainValidation() throws IOException {
        DummyContext ctx = new DummyContext();
        // gmail.com is not an approved institutional domain
        commandRouter.route(ctx, "REGISTER|bad_domain_user|test@gmail.com|Password123|" + testBatchId);

        assertTrue(ctx.sent.stream().anyMatch(s -> s.startsWith("ERROR|Email must belong to an approved institutional domain")),
                "Should reject public email domains");
    }

    @Test
    void testRegistrationPendingUntilVerified() throws IOException {
        String testUser = "p6_stud_" + System.currentTimeMillis();
        String testEmail = testUser + "@vcet.edu.in";
        String password = "SecretPassword123";

        DummyContext regCtx = new DummyContext();
        commandRouter.route(regCtx, "REGISTER|" + testUser + "|" + testEmail + "|" + password + "|" + testBatchId);

        assertTrue(regCtx.sent.stream().anyMatch(s -> s.startsWith("OK|VERIFY_SENT")),
                "Server should acknowledge registration with VERIFY_SENT");

        // Verify user created with PENDING status in DB
        Optional<User> userOpt = userDao.findByUsername(testUser);
        assertTrue(userOpt.isPresent(), "User should exist in DB");
        assertEquals(UserStatus.PENDING, userOpt.get().getStatus(), "User must be PENDING");

        // Mailer should have received the token
        String token = mockMailer.getLastToken(testEmail);
        assertNotNull(token, "Verification token should have been sent");
        assertEquals(6, token.length(), "Token should be 6 digits");

        // Attempt login while PENDING -> should be rejected
        DummyContext loginCtx = new DummyContext();
        commandRouter.route(loginCtx, "LOGIN|" + testUser + "|" + password);
        assertTrue(loginCtx.sent.stream().anyMatch(s -> s.contains("Account pending email verification")),
                "Login must fail for PENDING accounts");

        // Submit wrong token -> should fail
        DummyContext wrongVerifyCtx = new DummyContext();
        commandRouter.route(wrongVerifyCtx, "VERIFY|" + testEmail + "|999999");
        assertTrue(wrongVerifyCtx.sent.stream().anyMatch(s -> s.startsWith("ERROR|Invalid or expired")),
                "Wrong token should be rejected");

        // Submit correct token -> should activate account
        DummyContext correctVerifyCtx = new DummyContext();
        commandRouter.route(correctVerifyCtx, "VERIFY|" + testEmail + "|" + token);
        assertTrue(correctVerifyCtx.sent.stream().anyMatch(s -> s.startsWith("OK|VERIFIED")),
                "Correct verification should succeed");

        // Check user is now ACTIVE in DB
        User updatedUser = userDao.findById(userOpt.get().getUserId()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, updatedUser.getStatus(), "User must be ACTIVE after verification");

        // Attempting to reuse same token -> should fail
        DummyContext reuseCtx = new DummyContext();
        commandRouter.route(reuseCtx, "VERIFY|" + testEmail + "|" + token);
        assertTrue(reuseCtx.sent.stream().anyMatch(s -> s.startsWith("ERROR|")),
                "Reusing a consumed token must fail");

        // Now login should succeed
        AuthService.AuthResult authResult = authService.authenticate(testUser, password);
        assertTrue(authResult.success(), "Authentication should succeed after account is ACTIVE");
        assertNotNull(authResult.user());
        assertEquals(UserStatus.ACTIVE, authResult.user().getStatus());
    }

    @Test
    void testResendRateLimiter() throws IOException {
        String testUser = "p6_resend_" + System.currentTimeMillis();
        String testEmail = testUser + "@student.college.edu";
        String password = "SecretPassword123";

        DummyContext ctx = new DummyContext();
        commandRouter.route(ctx, "REGISTER|" + testUser + "|" + testEmail + "|" + password + "|" + testBatchId);

        // 1 initial token was sent on register.
        // Resend #1
        DummyContext r1 = new DummyContext();
        commandRouter.route(r1, "RESEND_VERIFY|" + testEmail);
        assertTrue(r1.sent.stream().anyMatch(s -> s.startsWith("OK|VERIFY_SENT")));

        // Resend #2
        DummyContext r2 = new DummyContext();
        commandRouter.route(r2, "RESEND_VERIFY|" + testEmail);
        assertTrue(r2.sent.stream().anyMatch(s -> s.startsWith("OK|VERIFY_SENT")));

        // Resend #3
        DummyContext r3 = new DummyContext();
        commandRouter.route(r3, "RESEND_VERIFY|" + testEmail);
        assertTrue(r3.sent.stream().anyMatch(s -> s.startsWith("OK|VERIFY_SENT")));

        // Resend #4 -> rate limit should trigger (maximum 3 resends per hour)
        DummyContext r4 = new DummyContext();
        commandRouter.route(r4, "RESEND_VERIFY|" + testEmail);
        assertTrue(r4.sent.stream().anyMatch(s -> s.contains("Rate limit exceeded")),
                "Should reject more than 3 verification resends within 1 hour");
    }
}
