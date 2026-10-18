import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-contained tests for {@link RateLimiter}.
 *
 * No external test framework is required — run with:
 *   javac RateLimiter.java RateLimiterTest.java && java RateLimiterTest
 */
public class RateLimiterTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testAllowsUpToLimit();
        testBlocksOverLimit();
        testSlidingWindowEviction();
        testConstructorValidation();
        testThreadSafety();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testAllowsUpToLimit() {
        RateLimiter limiter = new RateLimiter(3, 1000);
        long now = 1_000L;
        check("1st request allowed", limiter.allow(now));
        check("2nd request allowed", limiter.allow(now));
        check("3rd request allowed", limiter.allow(now));
    }

    static void testBlocksOverLimit() {
        RateLimiter limiter = new RateLimiter(2, 1000);
        long now = 1_000L;
        check("1st allowed", limiter.allow(now));
        check("2nd allowed", limiter.allow(now));
        check("3rd rate limited", !limiter.allow(now));
    }

    static void testSlidingWindowEviction() {
        RateLimiter limiter = new RateLimiter(2, 1000);
        check("t=0 allowed", limiter.allow(0));
        check("t=500 allowed", limiter.allow(500));
        check("t=900 rate limited", !limiter.allow(900));
        // First timestamp (t=0) falls out of the window at t=1000.
        check("t=1000 allowed after eviction", limiter.allow(1000));
        check("t=1000 rate limited again", !limiter.allow(1000));
    }

    static void testConstructorValidation() {
        check("non-positive maxRequests rejected",
                throwsIllegalArgument(() -> new RateLimiter(0, 1000)));
        check("non-positive windowMillis rejected",
                throwsIllegalArgument(() -> new RateLimiter(1, 0)));
    }

    static void testThreadSafety() throws InterruptedException {
        int permits = 1000;
        RateLimiter limiter = new RateLimiter(permits, 60_000);
        long now = 5_000L;
        int threads = 16;
        AtomicInteger allowed = new AtomicInteger();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    if (limiter.allow(now)) {
                        allowed.incrementAndGet();
                    }
                }
            });
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
        // Despite 16 threads racing, exactly `permits` requests must be allowed.
        check("concurrent allow count is exactly " + permits + " (got " + allowed.get() + ")",
                allowed.get() == permits);
    }

    // --- tiny assertion helpers ---

    static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    static boolean throwsIllegalArgument(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
