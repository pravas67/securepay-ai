package com.securepay.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAfterLimitAndRecoversWhenTheWindowPasses() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), clock);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.tryAcquire("u1")).isFalse();
        assertThat(limiter.tryAcquire("u2")).isTrue();

        clock.now = clock.now.plusSeconds(61);
        assertThat(limiter.isBlocked("u1")).isFalse();
        assertThat(limiter.tryAcquire("u1")).isTrue();
    }
}
