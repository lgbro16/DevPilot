package devpilot.Backend.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiRateLimiterTest {

    @Test
    @DisplayName("Should detect 429 and quota errors as rate limits")
    void shouldDetectRateLimits() {
        GeminiRateLimiter limiter = new GeminiRateLimiter(60);
        assertThat(limiter.isRateLimit(new RuntimeException("HTTP 429 Too Many Requests"))).isTrue();
        assertThat(limiter.isRateLimit(new RuntimeException("RESOURCE_EXHAUSTED: quota exceeded"))).isTrue();
        assertThat(limiter.isRateLimit(new RuntimeException("embed_content_free_tier_requests"))).isTrue();
        assertThat(limiter.isRateLimit(new RuntimeException("400 Bad Request"))).isFalse();
    }

    @Test
    @DisplayName("Should identify 429 and 5xx as transient errors")
    void shouldIdentifyTransientErrors() {
        GeminiRateLimiter limiter = new GeminiRateLimiter(60);
        assertThat(limiter.isTransientError(new RuntimeException("HTTP 429 Too Many Requests"))).isTrue();
        assertThat(limiter.isTransientError(new RuntimeException("503 Service Unavailable"))).isTrue();
        assertThat(limiter.isTransientError(new RuntimeException("Connection timed out"))).isTrue();
        assertThat(limiter.isTransientError(new RuntimeException("400 Bad Request"))).isFalse();
        assertThat(limiter.isTransientError(new RuntimeException("401 Unauthorized"))).isFalse();
    }

    @Test
    @DisplayName("Should retry transient error and succeed on second attempt")
    void shouldRetryTransientAndSucceed() throws Exception {
        GeminiRateLimiter limiter = new GeminiRateLimiter(90);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = limiter.executeWithRetry("testOp", () -> {
            int current = attempts.incrementAndGet();
            if (current == 1) {
                throw new RuntimeException("HTTP 429: rate limit exceeded, retry in 0s");
            }
            return "SUCCESS";
        });

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should stop retrying non-transient error immediately")
    void shouldNotRetryNonTransientErrors() {
        GeminiRateLimiter limiter = new GeminiRateLimiter(90);
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> limiter.executeWithRetry("nonTransientOp", () -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("400 Bad Request: Invalid model parameters");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }
}

