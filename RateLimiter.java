import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A simple sliding-window rate limiter.
 *
 * Allows at most {@code maxRequests} within any {@code windowMillis} time window.
 */
public class RateLimiter {
    private final int maxRequests;
    private final long windowMillis;
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
    public synchronized boolean allow(long nowMillis) {
        long windowStart = nowMillis - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
            timestamps.pollFirst();
        }
        if (timestamps.size() < maxRequests) {
            timestamps.addLast(nowMillis);
            return true;
        }
        return false;
    }

    /**
     * Attempts to allow a request at the current system time.
     *
     * @return {@code true} if the request is permitted, {@code false} if rate limited
     */
    public boolean allow() {
        return allow(System.currentTimeMillis());
    }

    public static void main(String[] args) {
        RateLimiter limiter = new RateLimiter(3, 1000);
        long now = System.currentTimeMillis();
        for (int i = 1; i <= 5; i++) {
            System.out.println("Request " + i + ": " + (limiter.allow(now) ? "allowed" : "rate limited"));
        }
    }
}
