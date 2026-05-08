package com.stucray.limen.audit.dispatch;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.Function;

/**
 * One declarative entry in {@link AuditRegistry}: the event class to listen
 * for, the {@code event_type} string written to the row, the listener
 * binding, and a projection function that reads the event and returns the
 * variable parts of the {@code audit_event} row. Constant boilerplate (id,
 * timestamp, request-context ip / user-agent) is filled in by
 * {@link AuditDispatcher}.
 *
 * <p>The projection may return {@code null} to opt out of writing — used by
 * the {@code login_success} rule when the principal isn't a tenant user
 * (e.g. an OAuth client token).
 */
record AuditRule<E>(
    Class<E> eventClass,
    String eventType,
    AuditBinding binding,
    Function<E, @Nullable Projection> project
) {

    /**
     * The variable parts of an {@link com.stucray.limen.audit.AuditEvent} row.
     * {@code ipOverride} is set only when the event itself carries the relevant
     * IP (e.g. {@code RateLimitHitEvent} captures the rate-limited request's IP
     * before any servlet request-context exists); when null the dispatcher
     * reads {@code RequestContextHolder}.
     */
    record Projection(
        @Nullable Long tenantId,
        @Nullable Long actorUserId,
        @Nullable String targetType,
        @Nullable String targetId,
        @Nullable String ipOverride,
        Map<String, Object> details
    ) {
        public static Projection of(
                @Nullable Long tenantId,
                @Nullable Long actorUserId,
                @Nullable String targetType,
                @Nullable String targetId,
                Map<String, Object> details) {
            return new Projection(tenantId, actorUserId, targetType, targetId, null, details);
        }

        /**
         * Common shape: {@code targetType="user"}, {@code targetId=String.valueOf(userId)}.
         * When {@code userId} is null (e.g. a "forgot password" request for an unknown
         * email) target fields collapse to null.
         */
        public static Projection ofUser(
                @Nullable Long tenantId,
                @Nullable Long actorUserId,
                @Nullable Long userId,
                Map<String, Object> details) {
            String targetType = userId == null ? null : "user";
            String targetId = userId == null ? null : String.valueOf(userId);
            return new Projection(tenantId, actorUserId, targetType, targetId, null, details);
        }

        public Projection withIp(@Nullable String ip) {
            return new Projection(tenantId, actorUserId, targetType, targetId, ip, details);
        }
    }
}
