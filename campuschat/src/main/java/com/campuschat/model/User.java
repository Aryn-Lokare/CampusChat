package com.campuschat.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_email", columnList = "email", unique = true),
    @Index(name = "idx_user_name", columnList = "user_name", unique = true),
    @Index(name = "idx_user_batch", columnList = "batch_id"),
    @Index(name = "idx_user_role", columnList = "role")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "user_name", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password", nullable = false, length = 100)
    private String password;

    @Convert(converter = com.campuschat.model.converter.RoleConverter.class)
    @Column(name = "role", length = 20)
    private Role role = Role.STUDENT;

    @Convert(converter = com.campuschat.model.converter.UserStatusConverter.class)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private Batch batch;

    @Column(name = "phone", length = 15)
    private String phone;

    public User() {}

    public User(String username, String email, String password, Role role, Batch batch) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.role = role != null ? role : Role.STUDENT;
        this.status = UserStatus.PENDING;
        this.batch = batch;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @Override
    public String toString() {
        return "User{" +
                "userId=" + userId +
                ", email='" + email + '\'' +
                ", username='" + username + '\'' +
                ", role=" + role +
                ", status=" + status +
                '}';
    }
}
