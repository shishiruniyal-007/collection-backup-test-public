import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * A thread-safe sliding-window rate limiter.
 *
 * Allows at most {@code maxRequests} within any {@code windowMillis} time window.
 * All access to the shared timestamp window is guarded by an internal lock, so
 * instances may be shared safely across multiple threads.
 */
public class RateLimiter {
    private static final Logger LOGGER = Logger.getLogger(RateLimiter.class.getName());

    private final int maxRequests;
    private final long windowMillis;
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by {@code lock}.
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RateLimiter(int maxRequests, long windowMillis) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive");
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    /**
     * Attempts to allow a request at the given timestamp.
     *
     * @param nowMillis the current time in milliseconds
     * @return {@code true} if the request is permitted, {@code false} if rate limited
     */

    /**
     * Attempts to allow a request at the current system time.
     *
     * @return {@code true} if the request is permitted, {@code false} if rate limited
     */
    public boolean allow() {
        return allow(System.currentTimeMillis());
    }

    public static void main(String[] args) {
        // Enable FINE-level debug logging for the demo.
        LOGGER.setLevel(java.util.logging.Level.FINE);
        java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
        handler.setLevel(java.util.logging.Level.FINE);
        LOGGER.addHandler(handler);
        LOGGER.setUseParentHandlers(false);

        RateLimiter limiter = new RateLimiter(3, 1000);
        long now = System.currentTimeMillis();
        for (int i = 1; i <= 5; i++) {
            System.out.println("Request " + i + ": " + (limiter.allow(now) ? "allowed" : "rate limited"));
        }
    }
}
