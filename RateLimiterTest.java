import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone test suite for {@link RateLimiter}.
 *
 * <p>Compile and run with:
 * <pre>
 *   javac RateLimiter.java RateLimiterTest.java
 *   java RateLimiterTest
 * </pre>
 */
public class RateLimiterTest {

    // -------------------------------------------------------------------------
    // Minimal assertion helpers
    // -------------------------------------------------------------------------

    private static int passed = 0;
    private static int failed = 0;

    private static void assertTrue(String label, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + label);
            passed++;
        } else {
            System.out.println("  FAIL: " + label);
            failed++;
        }
    }

    private static void assertFalse(String label, boolean condition) {
        assertTrue(label, !condition);
    }

    private static void assertThrows(String label, Class<? extends RuntimeException> type, Runnable block) {
        try {
            block.run();
            System.out.println("  FAIL: " + label + " (no exception thrown)");
            failed++;
        } catch (RuntimeException e) {
            if (type.isInstance(e)) {
                System.out.println("  PASS: " + label);
                passed++;
            } else {
                System.out.println("  FAIL: " + label + " (wrong exception: " + e.getClass().getSimpleName() + ")");
                failed++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /** Constructor rejects non-positive arguments. */
    static void testConstructorValidation() {
        System.out.println("\n[Constructor validation]");
        assertThrows("maxRequests=0 throws", IllegalArgumentException.class,
                () -> new RateLimiter(0, 1000));
        assertThrows("maxRequests=-1 throws", IllegalArgumentException.class,
                () -> new RateLimiter(-1, 1000));
        assertThrows("windowMillis=0 throws", IllegalArgumentException.class,
                () -> new RateLimiter(1, 0));
        assertThrows("windowMillis=-1 throws", IllegalArgumentException.class,
                () -> new RateLimiter(1, -1));
        // Valid construction should not throw
        try {
            new RateLimiter(1, 1);
            System.out.println("  PASS: valid args accepted");
            passed++;
        } catch (Exception e) {
            System.out.println("  FAIL: valid args threw " + e);
            failed++;
        }
    }

    /** Requests are allowed up to maxRequests within the window. */
    static void testBasicAllowAndDeny() {
        System.out.println("\n[Basic allow / deny]");
        RateLimiter limiter = new RateLimiter(3, 1000);
        long t = 0;
        assertTrue("1st request allowed", limiter.allow(t));
        assertTrue("2nd request allowed", limiter.allow(t));
        assertTrue("3rd request allowed", limiter.allow(t));
        assertFalse("4th request denied (limit reached)", limiter.allow(t));
        assertFalse("5th request denied (limit still reached)", limiter.allow(t));
    }

    /** Timestamps that fall outside the window are evicted, freeing capacity. */
    static void testWindowEviction() {
        System.out.println("\n[Window eviction]");
        long windowMillis = 1000;
        RateLimiter limiter = new RateLimiter(2, windowMillis);

        long t0 = 0;
        assertTrue("t=0: 1st allowed", limiter.allow(t0));
        assertTrue("t=0: 2nd allowed", limiter.allow(t0));
        assertFalse("t=0: 3rd denied", limiter.allow(t0));

        // Advance time past the window — the two t0 timestamps are evicted.
        long t1 = t0 + windowMillis + 1;
        assertTrue("t=1001: 1st allowed after eviction", limiter.allow(t1));
        assertTrue("t=1001: 2nd allowed after eviction", limiter.allow(t1));
        assertFalse("t=1001: 3rd denied (new window full)", limiter.allow(t1));
    }

    /** A timestamp exactly at the window boundary (windowStart) is evicted. */
    static void testBoundaryTimestampEvicted() {
        System.out.println("\n[Boundary timestamp eviction]");
        long windowMillis = 1000;
        RateLimiter limiter = new RateLimiter(1, windowMillis);

        long t0 = 500;
        assertTrue("t=500: allowed", limiter.allow(t0));

        // nowMillis=1500 → windowStart=500. Timestamp at 500 is evicted (<=windowStart).
        long t1 = t0 + windowMillis;      // 1500
        assertTrue("t=1500: allowed (boundary timestamp evicted)", limiter.allow(t1));
    }

    /** A fresh limiter with maxRequests=1 allows exactly one request per window. */
    static void testSingleRequestPerWindow() {
        System.out.println("\n[Single request per window]");
        RateLimiter limiter = new RateLimiter(1, 500);
        assertTrue("t=0: allowed",   limiter.allow(0));
        assertFalse("t=0: denied",   limiter.allow(0));
        assertTrue("t=501: allowed", limiter.allow(501));
        assertFalse("t=501: denied", limiter.allow(501));
    }

    /** The no-argument allow() delegates to allow(currentTimeMillis) and respects the limit. */
    static void testNoArgAllow() {
        System.out.println("\n[No-arg allow()]");
        RateLimiter limiter = new RateLimiter(2, 60_000);
        // All three calls happen within the same large window, so quota applies.
        assertTrue("1st no-arg call allowed", limiter.allow());
        assertTrue("2nd no-arg call allowed", limiter.allow());
        assertFalse("3rd no-arg call denied (limit reached)", limiter.allow());
    }

    /**
     * Concurrent threads hammer the limiter; the total number of ALLOWED
     * responses must never exceed maxRequests within any single window.
     */
    static void testConcurrentAccess() throws InterruptedException {
        System.out.println("\n[Concurrent access]");
        int maxRequests = 10;
        int threads     = 50;
        RateLimiter limiter = new RateLimiter(maxRequests, 10_000);

        AtomicInteger allowed = new AtomicInteger(0);
        CountDownLatch ready  = new CountDownLatch(threads);
        CountDownLatch go     = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.allow(System.currentTimeMillis())) {
                    allowed.incrementAndGet();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        int result = allowed.get();
        assertTrue("Allowed count <= maxRequests (" + result + " <= " + maxRequests + ")",
                result <= maxRequests);
        assertTrue("At least one request allowed (" + result + " >= 1)", result >= 1);
    }

    // -------------------------------------------------------------------------
    // Runner
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== RateLimiter tests ===");
        testConstructorValidation();
        testBasicAllowAndDeny();
        testWindowEviction();
        testBoundaryTimestampEvicted();
        testSingleRequestPerWindow();
        testNoArgAllow();
        testConcurrentAccess();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
