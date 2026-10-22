import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimiter} — sliding-window semantics.
 */
class RateLimiterTest {

    // -----------------------------------------------------------------------
    // Constructor validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects non-positive maxRequests")
    void constructor_rejectsNonPositiveMaxRequests() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(-1, 1000));
    }

    @Test
    @DisplayName("constructor rejects non-positive windowMillis")
    void constructor_rejectsNonPositiveWindowMillis() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(1, -500));
    }

    // -----------------------------------------------------------------------
    // Basic allow / deny logic
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("allows exactly maxRequests within a window")
    void allows_exactlyMaxRequestsInWindow() {
        RateLimiter limiter = new RateLimiter(3, 1000);
        long t = 0;

        assertTrue(limiter.allow(t),     "1st request should be allowed");
        assertTrue(limiter.allow(t + 1), "2nd request should be allowed");
        assertTrue(limiter.allow(t + 2), "3rd request should be allowed");
        assertFalse(limiter.allow(t + 3), "4th request should be rate-limited");
    }

    @Test
    @DisplayName("single-request limiter allows first, denies second at same timestamp")
    void singleRequest_allowsThenDenies() {
        RateLimiter limiter = new RateLimiter(1, 500);
        assertTrue(limiter.allow(100));
        assertFalse(limiter.allow(100));
    }

    // -----------------------------------------------------------------------
    // Sliding-window expiry
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("evicts expired timestamps so new requests are allowed after window expires")
    void allowsRequests_afterWindowExpires() {
        RateLimiter limiter = new RateLimiter(2, 1000);

        // Fill window at t=0 and t=1
        assertTrue(limiter.allow(0));
        assertTrue(limiter.allow(1));
        assertFalse(limiter.allow(2), "should be rate-limited inside window");

        // Advance past the window: the entries at t=0 and t=1 are now expired
        // windowStart = 1001 - 1000 = 1; only timestamps > 1 remain (none)
        assertTrue(limiter.allow(1001), "should be allowed after window expires");
        assertTrue(limiter.allow(1002), "second slot should still be free");
        assertFalse(limiter.allow(1003), "window is full again");
    }

    @Test
    @DisplayName("partial expiry: only timestamps inside window count")
    void partialExpiry_countsOnlyActiveTimestamps() {
        RateLimiter limiter = new RateLimiter(3, 1000);

        limiter.allow(0);   // expires at t > 1000
        limiter.allow(500); // expires at t > 1500
        limiter.allow(999); // expires at t > 1999

        // At t=1001 the entry at t=0 has expired (1001 - 1000 = 1, 0 <= 1)
        // Remaining: 500, 999  → 1 slot free
        assertTrue(limiter.allow(1001));
        assertFalse(limiter.allow(1002));
    }

    @Test
    @DisplayName("window boundary: timestamp exactly at windowStart is evicted")
    void windowBoundary_exactlyAtStart_isEvicted() {
        RateLimiter limiter = new RateLimiter(1, 1000);

        limiter.allow(0);
        // windowStart for t=1000 is 1000 - 1000 = 0; entry at 0 <= 0 → evicted
        assertTrue(limiter.allow(1000), "entry on exact boundary should be evicted");
    }

    // -----------------------------------------------------------------------
    // Edge / stress cases
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("allows all requests when each lands in a fresh window")
    void allowsAll_whenEachRequestIsInFreshWindow() {
        RateLimiter limiter = new RateLimiter(1, 1000);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allow((long) i * 1000), "request " + i + " should be allowed");
        }
    }

    @Test
    @DisplayName("no-arg allow() delegates to system time without throwing")
    void noArgAllow_usesSystemTime() {
        RateLimiter limiter = new RateLimiter(10, 5000);
        assertDoesNotThrow(limiter::allow);
    }

    // -----------------------------------------------------------------------
    // Thread safety
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("concurrent requests respect maxRequests under contention")
    void concurrent_respectsMaxRequests() throws InterruptedException {
        int maxRequests = 50;
        int threads     = 20;
        int callsEach   = 10; // 200 total calls, all at the same timestamp

        RateLimiter limiter = new RateLimiter(maxRequests, 10_000);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied  = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        long now = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < callsEach; i++) {
                        if (limiter.allow(now)) allowed.incrementAndGet();
                        else                    denied.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(maxRequests, allowed.get(),
                "exactly maxRequests calls should be allowed");
        assertEquals(threads * callsEach - maxRequests, denied.get(),
                "remaining calls should be denied");
    }
}
