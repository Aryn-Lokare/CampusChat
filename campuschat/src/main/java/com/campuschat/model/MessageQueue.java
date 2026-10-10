package com.campuschat.model;

import jakarta.persistence.*;

@Entity
@Table(name = "pending_messages", indexes = {
    @Index(name = "idx_queue_user_delivered", columnList = "user_id, delivered")
})
public class MessageQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "queue_id")
    private Integer queueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "delivered", nullable = false)
    private Boolean delivered = false;

    public MessageQueue() {}

    public MessageQueue(User user, Message message) {
        this.user = user;
        this.message = message;
        this.delivered = false;
    }

    public Integer getQueueId() {
        return queueId;
    }

    public void setQueueId(Integer queueId) {
        this.queueId = queueId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public Boolean getDelivered() {
        return delivered;
    }

    public void setDelivered(Boolean delivered) {
        this.delivered = delivered;
    }

    @Override
    public String toString() {
        return "MessageQueue{" +
                "queueId=" + queueId +
                ", delivered=" + delivered +
                '}';
    }
}
