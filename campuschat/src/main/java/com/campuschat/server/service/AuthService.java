package com.campuschat.server.service;

import com.campuschat.dao.UserDao;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Service for authentication, password hashing (bcrypt), and account state verification.
 */
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private final UserDao userDao;

    public AuthService() {
        this.userDao = new UserDao();
    }

    public AuthService(UserDao userDao) {
        this.userDao = userDao;
    }

    public static String hashPassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
    }

    public static boolean checkPassword(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null) return false;
        try {
            return BCrypt.checkpw(rawPassword, hashedPassword);
        } catch (IllegalArgumentException e) {
            logger.warn("Corrupt password hash encountered: {}", e.getMessage());
            return false;
        }
    }

    public AuthResult authenticate(String identifier, String password) {
        if (identifier == null || identifier.isBlank() || password == null || password.isBlank()) {
            return new AuthResult(false, null, "Identifier and password must not be empty");
        }

        Optional<User> userOpt = userDao.findByUsernameOrEmail(identifier.trim());
        if (userOpt.isEmpty()) {
            return new AuthResult(false, null, "Invalid username or password");
        }

        User user = userOpt.get();

        if (!checkPassword(password, user.getPassword())) {
            return new AuthResult(false, null, "Invalid username or password");
        }

        if (user.getStatus() == UserStatus.PENDING) {
            return new AuthResult(false, null, "Account pending email verification");
        }

        if (user.getStatus() == UserStatus.BANNED) {
            return new AuthResult(false, null, "Account is banned");
        }

        return new AuthResult(true, user, null);
    }

    public record AuthResult(boolean success, User user, String errorReason) {}
}
