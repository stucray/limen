package com.stucray.limen.oauth2;

/**
 * Thread-local holder for the OAuth2 tenant context set during per-tenant request routing.
 */
public final class TenantContext {

    private record Data(String slug, Long tenantId) {}

    private static final ThreadLocal<Data> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String slug, Long tenantId) {
        HOLDER.set(new Data(slug, tenantId));
    }

    public static String getSlug() {
        Data d = HOLDER.get();
        return d != null ? d.slug() : null;
    }

    public static Long getTenantId() {
        Data d = HOLDER.get();
        return d != null ? d.tenantId() : null;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
