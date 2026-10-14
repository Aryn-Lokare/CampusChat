package com.campuschat.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock Mailer implementation for development, testing, and demo fallback.
 * Prints the token to console and log, and provides query methods for test automation.
 */
public class MockMailer implements MailService {

    private static final Logger logger = LoggerFactory.getLogger(MockMailer.class);

    private final ConcurrentHashMap<String, String> lastTokens = new ConcurrentHashMap<>();

    @Override
    public boolean sendVerificationEmail(String toEmail, String token) {
        if (toEmail == null || token == null) return false;

        lastTokens.put(toEmail.trim().toLowerCase(), token);

        System.out.println("==================================================");
        System.out.println(" 📧 [MOCK EMAIL DISPATCHED]                       ");
        System.out.println(" To:    " + toEmail);
        System.out.println(" Token: " + token);
        System.out.println(" Expires in: 15 minutes");
        System.out.println(" Command to verify: VERIFY|" + token);
        System.out.println("==================================================");

        logger.info("Mock verification email dispatched to {}. Token: {}", toEmail, token);
        return true;
    }

    public String getLastTokenForEmail(String email) {
        return email != null ? lastTokens.get(email.trim().toLowerCase()) : null;
    }

    public String getLastToken(String email) {
        return getLastTokenForEmail(email);
    }

    public void clear() {
        lastTokens.clear();
    }
}
