package com.campuschat.server.service;

/**
 * Interface for dispatching notification and verification emails.
 */
public interface MailService {

    /**
     * Sends an email verification token to the specified user address.
     *
     * @param toEmail The recipient institutional email address
     * @param token   The raw one-time verification token
     * @return true if delivery was successful; false otherwise
     */
    boolean sendVerificationEmail(String toEmail, String token);
}
