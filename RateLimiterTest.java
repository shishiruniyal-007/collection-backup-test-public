import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-contained test suite for {@link RateLimiter}.
 *
 * Run with: {@code java RateLimiterTest}
 * Exit code 0 = all tests passed; exit code 1 = one or more failures.
 */
public class RateLimiterTest {

    private static int passed = 0;
    private static int failed = 0;

    // -----------------------------------------------------------------------
    // Assertion helpers
    // -----------------------------------------------------------------------

    private static void assertTrue(String label, boolean condition) {
        if (condition) {
            System.out.println("  PASS  " + label);
            passed++;
        } else {
            System.out.println("  FAIL  " + label);
            failed++;
        }
    }

    private static void assertEqual(String label, int expected, int actual) {
        if (expected == actual) {
            System.out.println("  PASS  " + label + " (expected=" + expected + ")");
            passed++;
        } else {
            System.out.println("  FAIL  " + label + " expected=" + expected + " actual=" + actual);
            failed++;
        }
    }

    private static void assertThrows(String label, Runnable r) {
        try {
            r.run();
            System.out.println("  FAIL  " + label + " (no exception thrown)");
            failed++;
        } catch (IllegalArgumentException e) {
            System.out.println("  PASS  " + label + " (" + e.getMessage() + ")");
            passed++;
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /** The first maxRequests calls within the window must all be allowed. */
    static void testBasicAllow() {
        System.out.println("\n[testBasicAllow]");
        RateLimiter limiter = new RateLimiter(3, 1000);
        long t = 1_000_000L;
        assertTrue("request 1 allowed", limiter.allow(t));
        assertTrue("request 2 allowed", limiter.allow(t));
        assertTrue("request 3 allowed", limiter.allow(t));
    }

    /** Once maxRequests have been recorded, the next call within the same window is denied. */
    static void testRateLimitExact() {
        System.out.println("\n[testRateLimitExact]");
        RateLimiter limiter = new RateLimiter(3, 1000);
        long t = 2_000_000L;
        limiter.allow(t);
        limiter.allow(t);
        limiter.allow(t);
        assertTrue("4th request denied", !limiter.allow(t));
        assertTrue("5th request also denied", !limiter.allow(t));
    }

    /**
     * After the window slides forward past the oldest timestamp, a slot should
     * become available again.
     */
    static void testSlidingWindowRecovery() {
        System.out.println("\n[testSlidingWindowRecovery]");
        RateLimiter limiter = new RateLimiter(3, 1000);
        long t = 0L;

        // Fill the window.
        limiter.allow(t);       // t=0
        limiter.allow(t + 100); // t=100
        limiter.allow(t + 200); // t=200 — window is full

        assertTrue("denied at t=500", !limiter.allow(t + 500));

        // Advance past the first timestamp: windowStart = (t+1001) - 1000 = t+1
        // so the entry at t=0 is evicted.
        assertTrue("allowed at t=1001", limiter.allow(t + 1001));
        // Advancing further evicts t=100 and t=200.
        assertTrue("allowed at t=1101", limiter.allow(t + 1101));
        assertTrue("allowed at t=1201", limiter.allow(t + 1201));
        // Window is full again.
        assertTrue("denied at t=1201 (full)", !limiter.allow(t + 1201));
    }

    /** Boundary: a timestamp exactly at windowStart should be evicted. */
    static void testWindowBoundaryEviction() {
        System.out.println("\n[testWindowBoundaryEviction]");
        // windowMillis = 1000; entry at t=0 is stale when nowMillis=1000 (windowStart=0).
        RateLimiter limiter = new RateLimiter(1, 1000);
        limiter.allow(0L);
        assertTrue("t=1000 evicts t=0, new request allowed", limiter.allow(1000L));
    }

    /** A single-request window should deny all but one simultaneous call. */
    static void testSingleRequestWindow() {
        System.out.println("\n[testSingleRequestWindow]");
        RateLimiter limiter = new RateLimiter(1, 5000);
        long t = 10_000L;
        assertTrue("first allowed", limiter.allow(t));
        assertTrue("second denied", !limiter.allow(t));
        assertTrue("third denied", !limiter.allow(t + 4999));
        assertTrue("allowed after window", limiter.allow(t + 5001));
    }

    /** Interleaved requests across two different time points. */
    static void testInterleavedRequests() {
        System.out.println("\n[testInterleavedRequests]");
        RateLimiter limiter = new RateLimiter(2, 1000);
        long base = 5_000L;

        assertTrue("t=base allowed", limiter.allow(base));          // window: [base]
        assertTrue("t=base+500 allowed", limiter.allow(base + 500)); // window: [base, base+500]
        assertTrue("t=base+501 denied", !limiter.allow(base + 501)); // still full

        // Advance 1001ms past base: base is evicted (windowStart = base+1)
        assertTrue("t=base+1001 allowed", limiter.allow(base + 1001)); // window: [base+500, base+1001]
        assertTrue("t=base+1002 denied", !limiter.allow(base + 1002)); // still full
    }

    /** Constructor must reject non-positive maxRequests. */
    static void testInvalidMaxRequests() {
        System.out.println("\n[testInvalidMaxRequests]");
        assertThrows("zero maxRequests", () -> new RateLimiter(0, 1000));
        assertThrows("negative maxRequests", () -> new RateLimiter(-1, 1000));
    }

    /** Constructor must reject non-positive windowMillis. */
    static void testInvalidWindowMillis() {
        System.out.println("\n[testInvalidWindowMillis]");
        assertThrows("zero windowMillis", () -> new RateLimiter(5, 0));
        assertThrows("negative windowMillis", () -> new RateLimiter(5, -100));
    }

    /**
     * Multi-threaded stress test: N threads each attempt one request at the same
     * logical timestamp. Exactly maxRequests should succeed.
     */
    static void testConcurrentAccess() throws InterruptedException {
        System.out.println("\n[testConcurrentAccess]");
        int maxReq = 10;
        int threads = 50;
        RateLimiter limiter = new RateLimiter(maxReq, 10_000);
        long t = System.currentTimeMillis();

        AtomicInteger allowedCount = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.allow(t)) allowedCount.incrementAndGet();
                done.countDown();
            });
        }
        ready.await();
        start.countDown();
        done.await();
        pool.shutdownNow();

        assertEqual("exactly maxRequests allowed under concurrent load", maxReq, allowedCount.get());
    }

    /** allow() (no-arg) must delegate and return a consistent result over real time. */
    static void testAllowNoArgDelegates() {
        System.out.println("\n[testAllowNoArgDelegates]");
        RateLimiter limiter = new RateLimiter(2, 60_000); // 60-second window
        assertTrue("first no-arg call allowed", limiter.allow());
        assertTrue("second no-arg call allowed", limiter.allow());
        assertTrue("third no-arg call denied", !limiter.allow());
    }

    /** Large burst followed by a full window slide should reset completely. */
    static void testFullWindowReset() {
        System.out.println("\n[testFullWindowReset]");
        RateLimiter limiter = new RateLimiter(5, 1000);
        long t = 100_000L;

        // Fill window at t.
        for (int i = 0; i < 5; i++) limiter.allow(t + i);
        assertTrue("denied while full", !limiter.allow(t + 5));

        // Jump a full window ahead — all slots evicted.
        long next = t + 1001;
        int allowed = 0;
        for (int i = 0; i < 5; i++) if (limiter.allow(next + i)) allowed++;
        assertEqual("all 5 slots available after full slide", 5, allowed);
        assertTrue("denied after reset fill", !limiter.allow(next + 5));
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== RateLimiter Test Suite ===");

        testBasicAllow();
        testRateLimitExact();
        testSlidingWindowRecovery();
        testWindowBoundaryEviction();
        testSingleRequestWindow();
        testInterleavedRequests();
        testInvalidMaxRequests();
        testInvalidWindowMillis();
        testConcurrentAccess();
        testAllowNoArgDelegates();
        testFullWindowReset();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
