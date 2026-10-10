package com.campuschat.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "batches")
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_id")
    private Integer batchId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "`year`", nullable = false)
    private Integer year;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "batch", fetch = FetchType.LAZY)
    private List<User> users = new ArrayList<>();

    public Batch() {}

    public Batch(String name, Integer year) {
        this.name = name;
        this.year = year;
        this.createdAt = Instant.now();
    }

    public Integer getBatchId() {
        return batchId;
    }

    public void setBatchId(Integer batchId) {
        this.batchId = batchId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    @Override
    public String toString() {
        return "Batch{" +
                "batchId=" + batchId +
                ", name='" + name + '\'' +
                ", year=" + year +
                '}';
    }
}
