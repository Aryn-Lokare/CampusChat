package com.campuschat.server.service;

import com.campuschat.dao.*;
import com.campuschat.dto.BatchDto;
import com.campuschat.dto.UserDto;
import com.campuschat.model.*;
import com.campuschat.server.network.ConnectionContext;
import com.campuschat.server.state.BatchRoomRegistry;
import com.campuschat.server.state.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Service providing administrative functions (FR-11, FR-16):
 * - Live stats reporting (online users, active sessions, pending queue, messages today)
 * - Batch creation and management
 * - User status moderation (ban/unban with immediate active socket disconnection)
 * - User role assignment (Student, Faculty, Admin)
 */
public class AdminService {

    private static final Logger logger = LoggerFactory.getLogger(AdminService.class);

    private final UserDao userDao;
    private final BatchDao batchDao;
    private final SessionDao sessionDao;
    private final MessageDao messageDao;
    private final QueueDao queueDao;
    private final SessionRegistry sessionRegistry;
    private final BatchRoomRegistry batchRoomRegistry;

    public AdminService(UserDao userDao, BatchDao batchDao, SessionDao sessionDao,
                        MessageDao messageDao, QueueDao queueDao,
                        SessionRegistry sessionRegistry, BatchRoomRegistry batchRoomRegistry) {
        this.userDao = userDao;
        this.batchDao = batchDao;
        this.sessionDao = sessionDao;
        this.messageDao = messageDao;
        this.queueDao = queueDao;
        this.sessionRegistry = sessionRegistry;
        this.batchRoomRegistry = batchRoomRegistry;
    }

    /**
     * FR-16: Returns live counts for the admin dashboard.
     * Format: STATS|onlineUsers|activeSessions|pendingQueue|messagesToday
     */
    public String getLiveStatsString() {
        int onlineUsers = sessionRegistry.getActiveSessionCount();
        long activeSessions = Math.max(onlineUsers, sessionDao.countActiveSessions());
        long pendingQueue = queueDao.countPendingQueue();
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        long messagesToday = messageDao.countMessagesSince(startOfDay);

        return String.format("STATS|%d|%d|%d|%d", onlineUsers, activeSessions, pendingQueue, messagesToday);
    }

    /**
     * FR-11: Creates a new academic batch.
     */
    public AdminResult createBatch(String name, String yearStr) {
        if (name == null || name.isBlank() || yearStr == null || yearStr.isBlank()) {
            return new AdminResult(false, "Batch name and year are required");
        }

        int year;
        try {
            year = Integer.parseInt(yearStr.trim());
        } catch (NumberFormatException e) {
            return new AdminResult(false, "Year must be a valid integer");
        }

        String batchName = name.trim();
        if (batchDao.findByName(batchName).isPresent()) {
            return new AdminResult(false, "Batch with name '" + batchName + "' already exists");
        }

        Batch batch = new Batch(batchName, year);
        Batch saved = batchDao.save(batch);
        logger.info("Admin created batch #{} ({}, {})", saved.getBatchId(), saved.getName(), saved.getYear());
        return new AdminResult(true, "BATCH_CREATED|" + saved.getBatchId() + "|" + saved.getName());
    }

    /**
     * FR-11: Updates user status (ACTIVE, BANNED, PENDING).
     * If status is BANNED: immediately disconnects active socket connection.
     */
    public AdminResult setUserStatus(String username, String statusStr) {
        if (username == null || username.isBlank() || statusStr == null || statusStr.isBlank()) {
            return new AdminResult(false, "Username and status are required");
        }

        UserStatus targetStatus;
        try {
            targetStatus = UserStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return new AdminResult(false, "Invalid status. Allowed: ACTIVE, BANNED, PENDING");
        }

        String uName = username.trim();
        Optional<User> userOpt = userDao.findByUsername(uName);
        if (userOpt.isEmpty()) {
            return new AdminResult(false, "User '" + uName + "' not found");
        }

        User user = userOpt.get();
        userDao.updateStatus(uName, targetStatus);
        logger.info("Admin updated status of user '{}' to {}", uName, targetStatus);

        // If user was banned, immediately terminate live socket and sessions
        if (targetStatus == UserStatus.BANNED) {
            terminateBannedUser(user);
        }

        return new AdminResult(true, "STATUS_UPDATED|" + uName + "|" + targetStatus.name());
    }

    /**
     * FR-11: Updates user role (STUDENT, FACULTY, ADMIN).
     */
    public AdminResult setUserRole(String username, String roleStr) {
        if (username == null || username.isBlank() || roleStr == null || roleStr.isBlank()) {
            return new AdminResult(false, "Username and role are required");
        }

        Role targetRole;
        try {
            targetRole = Role.valueOf(roleStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return new AdminResult(false, "Invalid role. Allowed: STUDENT, FACULTY, ADMIN");
        }

        String uName = username.trim();
        Optional<User> userOpt = userDao.findByUsername(uName);
        if (userOpt.isEmpty()) {
            return new AdminResult(false, "User '" + uName + "' not found");
        }

        User user = userOpt.get();
        userDao.updateRole(uName, targetRole);
        logger.info("Admin updated role of user '{}' to {}", uName, targetRole);

        // Update live context role if online
        ConnectionContext liveCtx = sessionRegistry.getContextByUserId(user.getUserId());
        if (liveCtx != null) {
            liveCtx.setRole(targetRole);
        }

        return new AdminResult(true, "ROLE_UPDATED|" + uName + "|" + targetRole.name());
    }

    /**
     * Terminates an online banned user's live TCP socket and invalidates sessions.
     */
    private void terminateBannedUser(User user) {
        Integer userId = user.getUserId();
        ConnectionContext liveCtx = sessionRegistry.getContextByUserId(userId);
        if (liveCtx != null) {
            logger.warn("Terminating live connection for banned user: {}", user.getUsername());
            liveCtx.send("ERROR|Account has been banned by administrator");
            liveCtx.closeAfterWrite();
            sessionRegistry.unregister(liveCtx);
            batchRoomRegistry.remove(liveCtx);
        }
        sessionDao.deactivateAllUserSessions(userId);
    }

    public List<UserDto> getAllUsers() {
        return userDao.findAll().stream().map(UserDao::toDto).toList();
    }

    public List<BatchDto> getAllBatches() {
        return batchDao.findAll().stream().map(BatchDao::toDto).toList();
    }

    public record AdminResult(boolean success, String message) {}
}
