package com.campuschat.server.service;

import com.campuschat.model.Role;
import com.campuschat.server.network.ConnectionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FR-10: Token-bucket rate limiter per student connection.
 * Refilled by a ScheduledExecutorService.
 * Faculty and Admins are explicitly exempt.
 */
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    public static final int DEFAULT_CAPACITY = 5;
    public static final int DEFAULT_REFILL_TOKENS = 1;
    public static final long DEFAULT_REFILL_INTERVAL_MS = 500; // 2 tokens/sec

    private final int capacity;
    private final int refillTokens;
    private final ConcurrentHashMap<ConnectionContext, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public RateLimiter() {
        this(DEFAULT_CAPACITY, DEFAULT_REFILL_TOKENS, DEFAULT_REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public RateLimiter(int capacity, int refillTokens, long refillInterval, TimeUnit timeUnit) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-refill");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::refillAll, refillInterval, refillInterval, timeUnit);
        logger.info("RateLimiter initialized with capacity {}, refill {} tokens every {} {}",
                capacity, refillTokens, refillInterval, timeUnit);
    }

    /**
     * Attempts to consume 1 token for the given connection.
     * Faculty and Admin accounts are exempt and always return true.
     *
     * @param ctx The connection context of the sender
     * @return true if allowed to send; false if throttled
     */
    public boolean tryConsume(ConnectionContext ctx) {
        if (ctx == null) return true;

        // Faculty and Admins are explicitly exempt from rate limiting (FR-10)
        if (ctx.getRole() != Role.STUDENT) {
            return true;
        }

        TokenBucket bucket = buckets.computeIfAbsent(ctx, c -> new TokenBucket(capacity));
        boolean allowed = bucket.tryConsume(1);
        if (!allowed) {
            logger.warn("Rate limit exceeded for student: {}", ctx.getUsername());
        }
        return allowed;
    }

    /**
     * Refills tokens across all active student buckets.
     */
    public void refillAll() {
        try {
            for (TokenBucket bucket : buckets.values()) {
                bucket.refill(refillTokens);
            }
        } catch (Exception e) {
            logger.error("Error refilling rate limit buckets: {}", e.getMessage(), e);
        }
    }

    public void remove(ConnectionContext ctx) {
        if (ctx != null) {
            buckets.remove(ctx);
        }
    }

    public int getAvailableTokens(ConnectionContext ctx) {
        if (ctx == null) return capacity;
        TokenBucket bucket = buckets.get(ctx);
        return bucket != null ? bucket.getTokens() : capacity;
    }

    public int getCapacity() {
        return capacity;
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("RateLimiter scheduler stopped");
    }

    /**
     * Thread-safe Token Bucket implementation.
     */
    public static class TokenBucket {
        private final int capacity;
        private final AtomicInteger tokens;

        public TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = new AtomicInteger(capacity);
        }

        public boolean tryConsume(int count) {
            while (true) {
                int current = tokens.get();
                if (current < count) {
                    return false;
                }
                if (tokens.compareAndSet(current, current - count)) {
                    return true;
                }
            }
        }

        public void refill(int count) {
            while (true) {
                int current = tokens.get();
                int updated = Math.min(capacity, current + count);
                if (tokens.compareAndSet(current, updated)) {
                    break;
                }
            }
        }

        public int getTokens() {
            return tokens.get();
        }
    }
}
