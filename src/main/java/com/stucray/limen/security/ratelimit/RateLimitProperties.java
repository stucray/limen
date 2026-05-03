package com.stucray.limen.security.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * Configuration shape for {@link RateLimitFilter}. Each entry in {@code rules}
 * is one (path-matcher, key-extractor, bucket-spec) tuple. Multiple rules can
 * target the same path — the filter consumes a token from each matching
 * bucket, so a request must pass them all.
 *
 * <p>State is per-process (a {@link java.util.concurrent.ConcurrentHashMap} of
 * buckets keyed on rule-id + extracted key). Postgres-backed shared state is
 * the v3.5 horizontal-scale story.
 */
@ConfigurationProperties("limen.rate-limit")
@Validated
public record RateLimitProperties(
    boolean enabled,
    @Valid Map<String, RuleSpec> rules
) {

    public RateLimitProperties {
        rules = rules == null ? Map.of() : Map.copyOf(rules);
    }

    /**
     * One rate-limit rule. {@code pathPattern} is a Java regex matched against
     * the raw request URI (see {@link jakarta.servlet.http.HttpServletRequest#getRequestURI()}),
     * so tenant-prefixed routes need an optional {@code (/t/[^/]+)?} segment.
     * {@code capacity} is the bucket depth; {@code refillTokens} per
     * {@code refillPeriod} sets the steady-state allowance via Bucket4j's
     * {@code Refill.greedy} (continuous, fractional refill).
     */
    public record RuleSpec(
        @NotBlank String pathPattern,
        @NotNull KeyType key,
        @Positive long capacity,
        @Positive long refillTokens,
        @NotNull Duration refillPeriod
    ) {}

    public enum KeyType { IP, CLIENT_ID }
}
