import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * A thread-safe token-bucket rate limiter.
 *
 * The bucket holds up to {@code capacity} tokens and refills continuously at a
 * rate of {@code refillTokens} per {@code refillPeriodMillis}. Each permitted
 * request consumes one token; a request is rejected when no token is available.
 * Tokens are refilled lazily based on elapsed time, so no background thread is
 * required. All access to the shared token state is guarded by an internal
 * lock, so instances may be shared safely across multiple threads.
 */
public class TokenBucket {
    private static final Logger LOGGER = Logger.getLogger(TokenBucket.class.getName());

    private final long capacity;
    private final long refillTokens;
    private final long refillPeriodMillis;
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by {@code lock}.
    private double availableTokens;
    private long lastRefillMillis;

    public TokenBucket(long capacity, long refillTokens, long refillPeriodMillis, long nowMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be positive");
        }
        if (refillPeriodMillis <= 0) {
            throw new IllegalArgumentException("refillPeriodMillis must be positive");
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriodMillis = refillPeriodMillis;
        this.availableTokens = capacity;
        this.lastRefillMillis = nowMillis;
    }

    /**
     * Attempts to allow a request at the given timestamp, consuming one token.
     *
     * @param nowMillis the current time in milliseconds
     * @return {@code true} if a token was available, {@code false} if rate limited
     */
    public boolean allow(long nowMillis) {
        lock.lock();
        try {
            refill(nowMillis);
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                LOGGER.fine(() -> "ALLOWED at " + nowMillis + " (" + String.format("%.2f", availableTokens)
                        + "/" + capacity + " tokens remaining)");
                return true;
            }
            LOGGER.fine(() -> "RATE LIMITED at " + nowMillis + " (" + String.format("%.2f", availableTokens)
                    + "/" + capacity + " tokens remaining)");
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to allow a request at the current system time.
     *
     * @return {@code true} if a token was available, {@code false} if rate limited
     */
    public boolean allow() {
        return allow(System.currentTimeMillis());
    }

    // Refills tokens based on time elapsed since the last refill. Caller must hold {@code lock}.
    private void refill(long nowMillis) {
        long elapsed = nowMillis - lastRefillMillis;
        if (elapsed <= 0) {
            return;
        }
        double generated = ((double) elapsed / refillPeriodMillis) * refillTokens;
        if (generated > 0) {
            double before = availableTokens;
            availableTokens = Math.min(capacity, availableTokens + generated);
            lastRefillMillis = nowMillis;
            LOGGER.fine(() -> "Refilled " + String.format("%.2f", availableTokens - before)
                    + " token(s) over " + elapsed + "ms (now " + String.format("%.2f", availableTokens) + ")");
        }
    }

    public static void main(String[] args) {
        // Enable FINE-level debug logging for the demo.
        LOGGER.setLevel(java.util.logging.Level.FINE);
        java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
        handler.setLevel(java.util.logging.Level.FINE);
        LOGGER.addHandler(handler);
        LOGGER.setUseParentHandlers(false);

        long now = System.currentTimeMillis();
        // Capacity 3, refilling 1 token every 500ms.
        TokenBucket bucket = new TokenBucket(3, 1, 500, now);

        // Drain the initial burst capacity, then get rate limited.
        for (int i = 1; i <= 5; i++) {
            System.out.println("Request " + i + ": " + (bucket.allow(now) ? "allowed" : "rate limited"));
        }

        // Advance time by 1s -> 2 tokens refilled.
        now += 1000;
        for (int i = 6; i <= 8; i++) {
            System.out.println("Request " + i + " (+1s): " + (bucket.allow(now) ? "allowed" : "rate limited"));
        }
    }
}
