package com.campuschat;

import com.campuschat.dao.UserDao;
import com.campuschat.model.Role;
import com.campuschat.model.User;
import com.campuschat.model.UserStatus;
import com.campuschat.server.service.AuthService;
import com.campuschat.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;

import java.util.Optional;

/**
 * Utility to instantly promote or create the admin_user account in MySQL.
 */
public class PromoteToAdmin {

    public static void main(String[] args) {
        String targetUsername = args.length > 0 ? args[0] : "admin_user";
        String targetPassword = args.length > 1 ? args[1] : "admin123";

        System.out.println("==================================================");
        System.out.println("       CampusChat - Admin Account Setup Tool      ");
        System.out.println("==================================================");

        try {
            HibernateUtil.getSessionFactory();
            UserDao userDao = new UserDao();

            Optional<User> existingUser = userDao.findByUsername(targetUsername);

            if (existingUser.isPresent()) {
                User user = existingUser.get();
                System.out.println("[INFO] Found existing user: " + targetUsername);
                System.out.println("[INFO] Current Role: " + user.getRole() + ", Status: " + user.getStatus());

                Transaction tx = null;
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    tx = session.beginTransaction();
                    user.setRole(Role.ADMIN);
                    user.setStatus(UserStatus.ACTIVE);
                    user.setBatch(null); // Admin has no batch assignment
                    user.setPassword(AuthService.hashPassword(targetPassword));
                    session.merge(user);
                    tx.commit();
                }

                System.out.println("[SUCCESS] Successfully promoted '" + targetUsername + "' to Role.ADMIN!");
                System.out.println("[SUCCESS] Password set to: " + targetPassword);
            } else {
                System.out.println("[INFO] User '" + targetUsername + "' not found. Creating new ADMIN user...");

                User newAdmin = new User(
                        targetUsername,
                        targetUsername + "@vcet.edu.in",
                        AuthService.hashPassword(targetPassword),
                        Role.ADMIN,
                        null
                );
                newAdmin.setStatus(UserStatus.ACTIVE);

                userDao.save(newAdmin);

                System.out.println("[SUCCESS] Created new user '" + targetUsername + "' with Role.ADMIN!");
                System.out.println("[SUCCESS] Password set to: " + targetPassword);
            }

            System.out.println("\n--------------------------------------------------");
            System.out.println("  Admin credentials ready:");
            System.out.println("  Username: " + targetUsername);
            System.out.println("  Password: " + targetPassword);
            System.out.println("--------------------------------------------------");
            System.out.println("\nNext step: Close and restart run-server.bat and run-gui.bat!");

        } catch (Throwable t) {
            System.err.println("[ERROR] Failed to promote/create admin user:");
            t.printStackTrace(System.err);
        } finally {
            HibernateUtil.shutdown();
        }
    }
}
