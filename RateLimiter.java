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
    public boolean allow(long nowMillis) {
        lock.lock();
        try {
            long windowStart = nowMillis - windowMillis;
            int before = timestamps.size();
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
                timestamps.pollFirst();
            }
            int evicted = before - timestamps.size();
            if (evicted > 0) {
                LOGGER.fine(() -> "Evicted " + evicted + " expired timestamp(s) before " + windowStart);
            }
            if (timestamps.size() < maxRequests) {
                timestamps.addLast(nowMillis);
                LOGGER.fine(() -> "ALLOWED at " + nowMillis + " (" + timestamps.size() + "/" + maxRequests + " in window)");
                return true;
            }
            LOGGER.fine(() -> "RATE LIMITED at " + nowMillis + " (" + timestamps.size() + "/" + maxRequests + " in window)");
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to allow a request at the current system time.
     *
     * @return {@code true} if the request is permitted, {@code false} if rate limited
     */
    public boolean allow() {
        return allow(System.currentTimeMillis());
    }
