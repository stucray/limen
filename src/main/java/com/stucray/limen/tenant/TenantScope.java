package com.stucray.limen.tenant;

/**
 * ScopedValue-based per-request tenant carrier: same `(slug, tenantId)` pair,
 * null-when-unbound semantics, Loom-friendly (automatic teardown, no
 * carrier-thread pinning).
 */
public final class TenantScope {

    public record Data(String slug, Long tenantId) {}

    public static final ScopedValue<Data> SCOPE = ScopedValue.newInstance();

    private TenantScope() {}

    public static String slug() {
        return SCOPE.isBound() ? SCOPE.get().slug() : null;
    }

    public static Long tenantId() {
        return SCOPE.isBound() ? SCOPE.get().tenantId() : null;
    }

    public static void run(String slug, Long tenantId, Runnable body) {
        ScopedValue.where(SCOPE, new Data(slug, tenantId)).run(body);
    }

    public static <R, X extends Throwable> R call(
        String slug, Long tenantId, ScopedValue.CallableOp<? extends R, X> body
    ) throws X {
        return ScopedValue.where(SCOPE, new Data(slug, tenantId)).call(body);
    }
}
