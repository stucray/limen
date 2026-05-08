package com.stucray.limen.security.ratelimit;

import com.stucray.limen.audit.events.RateLimitHitEvent;
import com.stucray.limen.security.ratelimit.RateLimitProperties.KeyType;
import com.stucray.limen.security.ratelimit.RateLimitProperties.RuleSpec;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * High-precedence servlet filter that token-bucket-limits pre-auth surfaces
 * (see {@link RateLimitProperties} for the configured rules). On bucket-empty
 * the response is HTTP 429 with a {@code Retry-After} header (seconds until
 * the next refill) and a {@link RateLimitHitEvent} is published so the audit
 * module records the event.
 *
 * <p>Order is {@link Ordered#HIGHEST_PRECEDENCE} so that throttled requests
 * never reach the routing or security filter chains — there is no point doing
 * tenant resolution or session lookup for a request we are about to reject.
 *
 * <p>Bucket state is a {@link ConcurrentHashMap} keyed on {@code (ruleId,
 * extractedKey)} — see class javadoc on {@link RateLimitProperties} for the
 * scaling story. {@link OncePerRequestFilter} guarantees we evaluate each
 * rule exactly once per request even if the request is forwarded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final ApplicationEventPublisher publisher;
    private final List<CompiledRule> compiledRules;
    private final ConcurrentHashMap<RuleKey, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimitFilter(RateLimitProperties properties, ApplicationEventPublisher publisher) {
        this.properties = properties;
        this.publisher = publisher;
        this.compiledRules = properties.rules().entrySet().stream()
            .map(e -> CompiledRule.of(e.getKey(), e.getValue()))
            .toList();
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        if (!properties.enabled() || compiledRules.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        for (CompiledRule rule : compiledRules) {
            if (!rule.matches(path)) {
                continue;
            }
            String key = rule.extractKey(request);
            Bucket bucket = buckets.computeIfAbsent(
                new RuleKey(rule.id(), key),
                k -> rule.newBucket()
            );
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long retryAfter = retryAfterSeconds(probe.getNanosToWaitForRefill());
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
                publisher.publishEvent(new RateLimitHitEvent(
                    rule.id(), key, path, request.getMethod(),
                    request.getRemoteAddr(), retryAfter));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Visible for tests: drop all per-key bucket state. Production callers
     * should never invoke this — it would let throttled clients bypass the
     * remaining wait of any in-flight bucket. Tests use it to keep one
     * scenario from polluting the next within a single Spring context.
     */
    void resetBucketsForTesting() {
        buckets.clear();
    }

    private static long retryAfterSeconds(long nanosToWait) {
        // Round up so a probe of "wait 1.2 seconds" yields Retry-After: 2;
        // returning 1 would tell the client to retry before the next refill.
        long whole = nanosToWait / 1_000_000_000L;
        return nanosToWait % 1_000_000_000L == 0 ? whole : whole + 1;
    }

    private record RuleKey(String ruleId, String extractedKey) {}

    private record CompiledRule(
        String id,
        Pattern pathPattern,
        KeyType keyType,
        long capacity,
        long refillTokens,
        java.time.Duration refillPeriod
    ) {
        static CompiledRule of(String id, RuleSpec spec) {
            return new CompiledRule(
                id,
                Pattern.compile(spec.pathPattern()),
                spec.key(),
                spec.capacity(),
                spec.refillTokens(),
                spec.refillPeriod()
            );
        }

        boolean matches(String path) {
            return pathPattern.matcher(path).matches();
        }

        String extractKey(HttpServletRequest request) {
            return switch (keyType) {
                case IP -> nullToFallback(request.getRemoteAddr(), "unknown-ip");
                case CLIENT_ID -> extractClientId(request);
            };
        }

        Bucket newBucket() {
            Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, refillPeriod)
                .build();
            return Bucket.builder().addLimit(limit).build();
        }

        private static String extractClientId(HttpServletRequest request) {
            // Form-post client auth (client_secret_post) carries client_id as a
            // form parameter; servlet container parses + caches it on first
            // read, so SAS still sees the same value downstream.
            String fromForm = request.getParameter("client_id");
            if (fromForm != null && !fromForm.isBlank()) {
                return fromForm;
            }
            // HTTP Basic client auth (client_secret_basic) carries client_id as
            // the basic-auth username.
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            String fromBasic = clientIdFromBasicAuth(authHeader);
            if (fromBasic != null) {
                return fromBasic;
            }
            // No identifiable client — bucket all anonymous attempts together
            // so a flood of unauthenticated token-endpoint hits still trips
            // the per-client rule (in addition to the per-IP rule).
            return "anonymous";
        }

        private static @Nullable String clientIdFromBasicAuth(@Nullable String authHeader) {
            if (authHeader == null || !authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
                return null;
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(authHeader.substring(6).trim());
                String credentials = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                int colon = credentials.indexOf(':');
                if (colon <= 0) {
                    return null;
                }
                String clientId = credentials.substring(0, colon);
                return clientId.isBlank() ? null : clientId;
            } catch (IllegalArgumentException malformedBase64) {
                return null;
            }
        }

        private static String nullToFallback(@Nullable String value, String fallback) {
            return value == null ? fallback : value;
        }
    }
}
