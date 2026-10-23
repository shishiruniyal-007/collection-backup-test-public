import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    // ------------------------------------------------------------------ basic

    @Test
    @DisplayName("All requests within the limit are allowed")
    void withinLimit() {
        RateLimiter limiter = new RateLimiter(3, 1_000);
        long t = 1_000L;
        assertTrue(limiter.allow(t));
        assertTrue(limiter.allow(t + 100));
        assertTrue(limiter.allow(t + 200));
    }

    @Test
    @DisplayName("The (maxRequests+1)-th request in the same window is rejected")
    void exceedLimit() {
        RateLimiter limiter = new RateLimiter(3, 1_000);
        long t = 1_000L;
        assertTrue(limiter.allow(t));
        assertTrue(limiter.allow(t + 100));
        assertTrue(limiter.allow(t + 200));
        assertFalse(limiter.allow(t + 300)); // 4th within same window → rate limited
    }

    // --------------------------------------------------------- sliding window

    @Test
    @DisplayName("Window slides: the oldest entry expires and frees a slot")
    void slidingWindowExpiresOneEntry() {
        RateLimiter limiter = new RateLimiter(3, 1_000);
        long t = 1_000L;
        limiter.allow(t);         // t=1000
        limiter.allow(t + 100);   // t=1100
        limiter.allow(t + 200);   // t=1200 → window full
        assertFalse(limiter.allow(t + 300)); // still within window → rejected

        // At t=2000 the window covers (2000-1000, 2000] = (1000, 2000].
        // t=1000 is exactly at windowStart (≤ windowStart) and is evicted.
        assertTrue(limiter.allow(t + 1_000)); // slot freed → allowed
    }

    @Test
    @DisplayName("After a full window elapses all capacity is restored")
    void fullWindowReset() {
        RateLimiter limiter = new RateLimiter(3, 1_000);
        long t = 0L;
        limiter.allow(t);
        limiter.allow(t + 100);
        limiter.allow(t + 200);

        long later = t + 2_000L; // 2 s ahead; every prior entry is expired
        assertTrue(limiter.allow(later));
        assertTrue(limiter.allow(later + 100));
        assertTrue(limiter.allow(later + 200));
        assertFalse(limiter.allow(later + 300)); // fresh window full again
    }

    @Test
    @DisplayName("maxRequests=1: second request in the same window is rejected")
    void singleRequestWindow() {
        RateLimiter limiter = new RateLimiter(1, 1_000);
        long t = 5_000L;
        assertTrue(limiter.allow(t));
        assertFalse(limiter.allow(t + 1));
        assertTrue(limiter.allow(t + 1_001)); // previous entry expired
    }

    @Test
    @DisplayName("Requests at exact window boundaries are handled correctly")
    void exactBoundary() {
        // windowMillis=1000; three requests fill the window at t=0,1,2.
        RateLimiter limiter = new RateLimiter(3, 1_000);
        limiter.allow(0L);
        limiter.allow(1L);
        limiter.allow(2L);
        // At t=1000: windowStart=0; entry at t=0 is evicted (0 <= 0).
        assertTrue(limiter.allow(1_000L));
    }

    // ----------------------------------------------- convenience overload

    @Test
    @DisplayName("allow() delegates to allow(System.currentTimeMillis())")
    void convenientAllowPermitsRequest() {
        // Large burst budget so wall-clock movement never causes a false failure.
        RateLimiter limiter = new RateLimiter(1_000, 60_000);
        assertTrue(limiter.allow());
    }

    // ------------------------------------------ constructor validation

    @Test
    @DisplayName("Constructor rejects zero or negative maxRequests")
    void invalidMaxRequests() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(-5, 1_000));
    }

    @Test
    @DisplayName("Constructor rejects zero or negative windowMillis")
    void invalidWindowMillis() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(10, -1));
    }

    // ------------------------------------------------------- concurrency

    @Test
    @DisplayName("Concurrent callers never exceed maxRequests in the same window")
    void concurrentAccessRespectsLimit() throws InterruptedException {
        final int maxRequests = 50;
        final RateLimiter limiter = new RateLimiter(maxRequests, 10_000);
        final long fixedTime = System.currentTimeMillis();

        final int threadCount = 10;
        final int callsPerThread = 20; // 200 total calls; only 50 should be allowed
        final AtomicInteger allowed = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    ready.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        if (limiter.allow(fixedTime)) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.countDown(); // release all threads simultaneously
        assertTrue(done.await(10, TimeUnit.SECONDS), "Threads did not finish in time");

        assertEquals(maxRequests, allowed.get(),
                "Exactly maxRequests should be granted across all concurrent threads");
    }
}
