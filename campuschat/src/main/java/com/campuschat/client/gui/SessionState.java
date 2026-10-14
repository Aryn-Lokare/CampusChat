package com.campuschat.client.gui;

import com.campuschat.model.Role;

/**
 * Holds authenticated user session state for the client GUI.
 */
public class SessionState {

    private String username;
    private String email;
    private Role role;
    private Integer batchId;
    private String sessionToken;

    public SessionState() {
    }

    public void update(String username, Role role, Integer batchId, String sessionToken) {
        this.username = username;
        this.role = role;
        this.batchId = batchId;
        this.sessionToken = sessionToken;
    }

    public void clear() {
        this.username = null;
        this.email = null;
        this.role = null;
        this.batchId = null;
        this.sessionToken = null;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Integer getBatchId() {
        return batchId;
    }

    public void setBatchId(Integer batchId) {
        this.batchId = batchId;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public boolean isFaculty() {
        return role == Role.FACULTY;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
