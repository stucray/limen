package com.stucray.limen.audit.events;

import org.jspecify.annotations.Nullable;

/**
 * A pre-auth request was rejected with HTTP 429 because its bucket was empty.
 * Emitted by {@code RateLimitFilter} immediately before writing the response.
 *
 * <p>Pre-auth means the rate-limited request never reaches a controller and
 * never opens a database transaction — emit uses a plain
 * {@link org.springframework.context.event.EventListener} (the audit listener
 * picks the matching annotation automatically), not a transactional one.
 *
 * <p>{@code key} is the value the filter rule extracted from the request
 * (an IP address, an OAuth client_id, or {@code "anonymous"} when a per-client
 * rule fired on a request without identifiable client credentials). It is
 * captured for diagnostic value, not joined back to any other table.
 */
public record RateLimitHitEvent(
    String ruleId,
    @Nullable String key,
    String path,
    String method,
    @Nullable String ip,
    long retryAfterSeconds
) {}
