package com.campuschat.server.service;

import com.campuschat.dao.BatchDao;
import com.campuschat.dao.UserDao;
import com.campuschat.dao.VerificationDao;
import com.campuschat.model.Batch;
import com.campuschat.model.EmailVerification;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * FR-13 & FR-15: Registration and email verification service.
 * Enforces institutional email domain restrictions, manages account activation,
 * hashes one-time verification tokens with SHA-256, and throttles resends.
 */
public class RegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationService.class);

    private final UserDao userDao;
    private final BatchDao batchDao;
    private final VerificationDao verificationDao;
    private final MailService mailService;

    private final Set<String> allowedDomains = new HashSet<>(Arrays.asList(
            "college.edu", "vcet.edu.in", "student.college.edu", "university.edu"
    ));
    private final SecureRandom random = new SecureRandom();

    public RegistrationService(UserDao userDao, BatchDao batchDao, VerificationDao verificationDao, MailService mailService) {
        this.userDao = userDao;
        this.batchDao = batchDao;
        this.verificationDao = verificationDao;
        this.mailService = mailService;
    }

    public static String hashToken(String rawToken) {
        if (rawToken == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public boolean isValidInstitutionalEmail(String email) {
        if (email == null || !email.contains("@")) return false;
        String domain = email.substring(email.lastIndexOf("@") + 1).trim().toLowerCase();
        for (String allowed : allowedDomains) {
            if (domain.equalsIgnoreCase(allowed) || domain.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    public void addAllowedDomain(String domain) {
        if (domain != null && !domain.isBlank()) {
            allowedDomains.add(domain.trim().toLowerCase());
        }
    }

    /**
     * FR-15: Registers a new student account as PENDING and dispatches an email verification token.
     */
    public RegistrationResult register(String username, String email, String password, Integer batchId) {
        if (username == null || username.isBlank() || email == null || email.isBlank() || password == null || password.isBlank()) {
            return new RegistrationResult(false, "Username, email, and password are required");
        }

        String uName = username.trim();
        String mail = email.trim().toLowerCase();

        if (password.length() < 6) {
            return new RegistrationResult(false, "Password must be at least 6 characters long");
        }

        if (!isValidInstitutionalEmail(mail)) {
            return new RegistrationResult(false, "Email must belong to an approved institutional domain (e.g. @college.edu or @vcet.edu.in)");
        }

        if (userDao.findByUsername(uName).isPresent()) {
            return new RegistrationResult(false, "Username already exists");
        }

        if (userDao.findByUsernameOrEmail(mail).isPresent()) {
            return new RegistrationResult(false, "Email is already registered");
        }

        Batch batch = null;
        if (batchId != null) {
            batch = batchDao.findById(batchId).orElse(null);
            if (batch == null) {
                return new RegistrationResult(false, "Batch ID " + batchId + " does not exist. A valid batch is required.");
            }
        } else {
            return new RegistrationResult(false, "Batch is required for students. Batch cannot be empty.");
        }

        // 1. Create user in PENDING status
        String passwordHash = AuthService.hashPassword(password);
        User user = new User(uName, mail, passwordHash, Role.STUDENT, batch);
        user.setStatus(UserStatus.PENDING);
        userDao.save(user);

        // 2. Generate 6-digit one-time token and save its SHA-256 hash
        String rawToken = String.format("%06d", random.nextInt(1_000_000));
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);

        EmailVerification verification = new EmailVerification(user, tokenHash, expiresAt);
        verificationDao.save(verification);

        // 3. Dispatch raw token via mail service
        mailService.sendVerificationEmail(mail, rawToken);

        logger.info("Registered user {} with status PENDING. Verification dispatched to {}", uName, mail);
        return new RegistrationResult(true, "VERIFY_SENT");
    }

    /**
     * FR-15: Validates verification token, marks it used, and transitions account to ACTIVE.
     */
    public VerificationResult verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return new VerificationResult(false, "Verification token cannot be empty");
        }

        String tokenHash = hashToken(rawToken.trim());
        Optional<EmailVerification> vOpt = verificationDao.findValidByTokenHash(tokenHash);

        if (vOpt.isEmpty()) {
            return new VerificationResult(false, "Invalid or expired verification token");
        }

        EmailVerification verification = vOpt.get();
        if (verification.getUsed() || verification.isExpired()) {
            return new VerificationResult(false, "Verification token is already used or expired");
        }

        // Mark token used
        verificationDao.markUsed(verification.getVerificationId());

        // Activate user
        User user = verification.getUser();
        userDao.updateStatus(user.getUsername(), UserStatus.ACTIVE);

        logger.info("Account verified and activated for user: {}", user.getUsername());
        return new VerificationResult(true, "VERIFIED");
    }

    /**
     * FR-15: Resends a new token, limited to 3 per hour per email address.
     */
    public ResendResult resendVerification(String email) {
        if (email == null || email.isBlank()) {
            return new ResendResult(false, "Email address is required");
        }

        String mail = email.trim().toLowerCase();
        Optional<User> userOpt = userDao.findByUsernameOrEmail(mail);
        if (userOpt.isEmpty()) {
            return new ResendResult(false, "No account found with this email");
        }

        User user = userOpt.get();
        if (user.getStatus() != UserStatus.PENDING) {
            return new ResendResult(false, "Account is already active or cannot be verified");
        }

        // Enforce rate limit of 3 resends per hour per address (total 4 tokens including registration)
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        long recentRequests = verificationDao.countRecentRequestsForUser(user.getUserId(), oneHourAgo);
        if (recentRequests > 3) {
            logger.warn("Resend limit reached for email {}: {} requests in last hour", mail, recentRequests);
            return new ResendResult(false, "Rate limit exceeded. Maximum 3 verification emails per hour.");
        }

        // Generate new token
        String rawToken = String.format("%06d", random.nextInt(1_000_000));
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);

        EmailVerification verification = new EmailVerification(user, tokenHash, expiresAt);
        verificationDao.save(verification);

        mailService.sendVerificationEmail(mail, rawToken);

        logger.info("Resent verification token for user {}", user.getUsername());
        return new ResendResult(true, "VERIFY_SENT");
    }

    public record RegistrationResult(boolean success, String message) {}
    public record VerificationResult(boolean success, String message) {}
    public record ResendResult(boolean success, String message) {}
}
