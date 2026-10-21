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

        if (targetRole == Role.STUDENT && user.getBatch() == null) {
            List<com.campuschat.model.Batch> batches = batchDao.findAll();
            if (batches.isEmpty()) {
                return new AdminResult(false, "Cannot set role to STUDENT: Student must belong to a batch, but no batches exist. Please create a batch first.");
            }
            userDao.updateBatch(uName, batches.get(0).getBatchId());
            logger.info("Auto-assigned student '{}' to batch #{}", uName, batches.get(0).getBatchId());
        } else if (targetRole == Role.ADMIN) {
            // PRD Section 4: batch must be NULL when role is ADMIN
            userDao.updateBatch(uName, null);
        }

        userDao.updateRole(uName, targetRole);
        logger.info("Admin updated role of user '{}' to {}", uName, targetRole);

        // Update live context role if online
        ConnectionContext liveCtx = sessionRegistry.getContextByUserId(user.getUserId());
        if (liveCtx != null) {
            liveCtx.setRole(targetRole);
            if (targetRole == Role.ADMIN) {
                liveCtx.setBatchId(null);
            }
        }

        return new AdminResult(true, "ROLE_UPDATED|" + uName + "|" + targetRole.name());
    }

    /**
     * FR-11: Assigns or updates a user's batch. (Students cannot have batch = null).
     */
    public AdminResult setUserBatch(String username, String batchIdStr) {
        if (username == null || username.isBlank() || batchIdStr == null || batchIdStr.isBlank()) {
            return new AdminResult(false, "Username and batch ID are required");
        }

        int bId;
        try {
            bId = Integer.parseInt(batchIdStr.trim());
        } catch (NumberFormatException e) {
            return new AdminResult(false, "Batch ID must be a valid integer");
        }

        String uName = username.trim();
        Optional<User> userOpt = userDao.findByUsername(uName);
        if (userOpt.isEmpty()) {
            return new AdminResult(false, "User '" + uName + "' not found");
        }

        User user = userOpt.get();
        if (user.getRole() == Role.ADMIN) {
            return new AdminResult(false, "Cannot assign batch to ADMIN: Admin accounts cannot belong to a batch");
        }

        Optional<com.campuschat.model.Batch> batchOpt = batchDao.findById(bId);
        if (batchOpt.isEmpty()) {
            return new AdminResult(false, "Batch #" + bId + " does not exist");
        }

        userDao.updateBatch(uName, bId);
        logger.info("Admin updated batch of user '{}' to #{}", uName, bId);

        // Update live connection context and batch rooms if online
        ConnectionContext liveCtx = sessionRegistry.getContextByUserId(user.getUserId());
        if (liveCtx != null) {
            batchRoomRegistry.remove(liveCtx);
            liveCtx.setBatchId(bId);
            batchRoomRegistry.join(bId, liveCtx);
            liveCtx.send("INFO|Your batch assignment was updated to Batch #" + bId);
        }

        return new AdminResult(true, "BATCH_UPDATED|" + uName + "|" + bId);
    }

    /**
     * FR-11: Deletes a batch from the system and unassigns affected users.
     */
    public AdminResult deleteBatch(String batchIdStr) {
        if (batchIdStr == null || batchIdStr.isBlank()) {
            return new AdminResult(false, "Batch ID is required");
        }

        int bId;
        try {
            bId = Integer.parseInt(batchIdStr.trim());
        } catch (NumberFormatException e) {
            return new AdminResult(false, "Batch ID must be a valid integer");
        }

        Optional<com.campuschat.model.Batch> batchOpt = batchDao.findById(bId);
        if (batchOpt.isEmpty()) {
            return new AdminResult(false, "Batch #" + bId + " does not exist");
        }

        // Notify online members of this batch
        java.util.Set<ConnectionContext> onlineMembers = batchRoomRegistry.getMembers(bId);
        for (ConnectionContext ctx : onlineMembers) {
            ctx.send("INFO|Your batch room #" + bId + " has been deleted by an administrator.");
            ctx.setBatchId(null);
        }

        boolean deleted = batchDao.deleteById(bId);
        if (deleted) {
            logger.info("Admin deleted batch #{}", bId);
            return new AdminResult(true, "BATCH_DELETED|" + bId);
        } else {
            return new AdminResult(false, "Failed to delete batch #" + bId);
        }
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
