package devpilot.Backend.service.ai;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Controlled rate limiter and exponential backoff retry handler for Gemini API calls.
 * Enforces requests-per-minute (RPM) limits (free-tier limit: 100 RPM, default: 60 RPM)
 * and manages graceful cooldown on HTTP 429 rate limit events.
 */
@Component
@Slf4j
public class GeminiRateLimiter {

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 32_000L;
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("(?i)(?:retry[- ]after|wait|in)\\s*[:=]?\\s*(\\d+)\\s*(?:s|sec|seconds)?");

    private final long minIntervalMs;
    private final Semaphore concurrencySemaphore;
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final AtomicLong cooldownUntilTime = new AtomicLong(0);
    private final Random random = new Random();

    public GeminiRateLimiter(@Value("${app.gemini.rate-limit-rpm:60}") int requestsPerMinute) {
        int rpm = Math.max(1, Math.min(requestsPerMinute, 90));
        this.minIntervalMs = 60_000L / rpm;
        this.concurrencySemaphore = new Semaphore(1, true);
        log.info("Initialized GeminiRateLimiter with {} RPM (min interval: {}ms)", rpm, minIntervalMs);
    }

    /**
     * Pauses if needed to satisfy the rate limit and respect any active cooldown.
     */
    public void acquire() {
        try {
            concurrencySemaphore.acquire();
            try {
                long now = System.currentTimeMillis();

                // Respect active cooldown from previous 429 responses
                long cooldownUntil = cooldownUntilTime.get();
                if (cooldownUntil > now) {
                    long waitMs = cooldownUntil - now;
                    log.warn("Gemini API in active cooldown. Pausing for {}ms before request", waitMs);
                    Thread.sleep(waitMs);
                    now = System.currentTimeMillis();
                }

                // Enforce minimum interval between consecutive requests
                long last = lastRequestTime.get();
                long elapsed = now - last;
                if (elapsed < minIntervalMs) {
                    long sleepMs = minIntervalMs - elapsed;
                    Thread.sleep(sleepMs);
                }

                lastRequestTime.set(System.currentTimeMillis());
            } finally {
                concurrencySemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for rate limiter", e);
        }
    }

    /**
     * Records a 429 rate limit / quota event and sets a global cooldown period.
     */
    public void recordRateLimit(long cooldownMs) {
        long target = System.currentTimeMillis() + Math.max(cooldownMs, 5_000L);
        cooldownUntilTime.updateAndGet(current -> Math.max(current, target));
        log.warn("Gemini 429 Rate Limit encountered. Imposed global cooldown for {}ms", cooldownMs);
    }

    /**
     * Executes an operation with exponential backoff and jitter, retrying only transient errors.
     */
    public <T> T executeWithRetry(String operationName, CheckedSupplier<T> supplier) throws Exception {
        int attempt = 0;
        long backoffMs = INITIAL_BACKOFF_MS;

        while (true) {
            attempt++;
            acquire();
            try {
                return supplier.get();
            } catch (Exception ex) {
                boolean isTransient = isTransientError(ex);
                if (!isTransient || attempt >= MAX_RETRIES) {
                    log.error("Operation '{}' failed on attempt {}/{} (isTransient={}): {}",
                            operationName, attempt, MAX_RETRIES, isTransient, ex.getMessage());
                    throw ex;
                }

                long retryDelayMs = parseRetryAfter(ex).toMillis();
                if (retryDelayMs <= 0) {
                    long jitter = random.nextInt(1000);
                    retryDelayMs = Math.min(backoffMs + jitter, MAX_BACKOFF_MS);
                    backoffMs *= 2;
                }

                if (isRateLimit(ex)) {
                    recordRateLimit(retryDelayMs);
                }

                log.warn("Operation '{}' encountered transient error on attempt {}/{}: {}. Retrying in {}ms...",
                        operationName, attempt, MAX_RETRIES, ex.getMessage(), retryDelayMs);

                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during backoff retry", ie);
                }
            }
        }
    }

    public boolean isTransientError(Throwable ex) {
        if (ex == null) return false;
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        if (isRateLimit(ex)) return true;
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")) return true;
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection reset")) return true;
        if (msg.contains("resource_exhausted") || msg.contains("quota")) return true;

        if (ex.getCause() != null && ex.getCause() != ex) {
            return isTransientError(ex.getCause());
        }
        return false;
    }

    public boolean isRateLimit(Throwable ex) {
        if (ex == null) return false;
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("429") || msg.contains("too many requests") || msg.contains("resource_exhausted") 
                || msg.contains("quota") || msg.contains("free_tier_requests")) {
            return true;
        }
        if (ex.getCause() != null && ex.getCause() != ex) {
            return isRateLimit(ex.getCause());
        }
        return false;
    }

    private Duration parseRetryAfter(Throwable ex) {
        if (ex == null || ex.getMessage() == null) return Duration.ZERO;
        Matcher matcher = RETRY_AFTER_PATTERN.matcher(ex.getMessage());
        if (matcher.find()) {
            try {
                long seconds = Long.parseLong(matcher.group(1));
                return Duration.ofSeconds(Math.min(seconds, 60));
            } catch (NumberFormatException ignored) {
            }
        }
        return Duration.ZERO;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}

