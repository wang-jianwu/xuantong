package cloud.xuantong.client.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarningRateLimiterTest {
    @Test
    void emitsOneWarningPerWindowAndCarriesTheSuppressedCountForward() {
        AtomicLong clock = new AtomicLong(1L);
        WarningRateLimiter limiter = new WarningRateLimiter(
                Duration.ofSeconds(30), clock::get);

        WarningRateLimiter.Decision first = limiter.acquire();
        assertTrue(first.allowed());
        assertEquals(0L, first.suppressedSinceLast());
        for (int i = 0; i < 1_000; i++) {
            assertFalse(limiter.acquire().allowed());
        }

        clock.addAndGet(Duration.ofSeconds(30).toNanos());
        WarningRateLimiter.Decision next = limiter.acquire();
        assertTrue(next.allowed());
        assertEquals(1_000L, next.suppressedSinceLast());
        assertFalse(limiter.acquire().allowed());
    }

    @Test
    void rejectsNonPositiveIntervals() {
        assertThrows(IllegalArgumentException.class,
                () -> new WarningRateLimiter(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new WarningRateLimiter(Duration.ofMillis(-1)));
    }
}
