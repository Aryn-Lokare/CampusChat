package com.campuschat.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "session", indexes = {
    @Index(name = "idx_session_token", columnList = "session_token", unique = true),
    @Index(name = "idx_session_user", columnList = "user_id")
})
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Integer sessionId;

    @Column(name = "session_token", length = 64, unique = true)
    private String sessionToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private Batch batch;

    @Column(name = "is_active")
    private Boolean active = true;

    @Column(name = "last_seen_time")
    private Instant lastSeenTime = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Column(name = "device", length = 50)
    private String device = "Desktop";

    public UserSession() {}

    public UserSession(String sessionToken, User user, Batch batch, String device) {
        this.sessionToken = sessionToken;
        this.user = user;
        this.batch = batch;
        this.device = device != null ? device : "Desktop";
        this.active = true;
        this.lastSeenTime = Instant.now();
    }

    public Integer getSessionId() {
        return sessionId;
    }

    public void setSessionId(Integer sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Instant getLastSeenTime() {
        return lastSeenTime;
    }

    public void setLastSeenTime(Instant lastSeenTime) {
        this.lastSeenTime = lastSeenTime;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    @Override
    public String toString() {
        return "UserSession{" +
                "sessionId=" + sessionId +
                ", sessionToken='" + sessionToken + '\'' +
                ", active=" + active +
                ", lastSeenTime=" + lastSeenTime +
                '}';
    }
}
