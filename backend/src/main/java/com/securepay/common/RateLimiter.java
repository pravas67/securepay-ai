package com.securepay.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limiter kept in memory. Good enough for a single instance; with several instances
 * this would move to Redis or the API gateway.
 */
public class RateLimiter {

    private final int maxEvents;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public RateLimiter(int maxEvents, Duration window) {
        this(maxEvents, window, Clock.systemUTC());
    }

    RateLimiter(int maxEvents, Duration window, Clock clock) {
        this.maxEvents = maxEvents;
        this.window = window;
        this.clock = clock;
    }

    /** Records an event for {@code key} and returns false if the key is over its limit. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Deque<Instant> deque = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, now);
            if (deque.size() >= maxEvents) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /** True if {@code key} is over its limit, without recording anything. */
    public boolean isBlocked(String key) {
        Deque<Instant> deque = events.get(key);
        if (deque == null) {
            return false;
        }
        synchronized (deque) {
            prune(deque, clock.instant());
            return deque.size() >= maxEvents;
        }
    }

    public void reset(String key) {
        events.remove(key);
    }

    private void prune(Deque<Instant> deque, Instant now) {
        Instant cutoff = now.minus(window);
        while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
            deque.removeFirst();
        }
    }
}
