package com.stucray.limen.ui.support;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;

/**
 * Per-role login helpers. All three drive the real login form (no test-only backdoor,
 * no synthesised SecurityContext) — login is exercised on every test that uses these.
 *
 * <p>Methods perform the navigate + fill + submit + wait-for-navigation. Callers
 * construct the next typed page object themselves (each post-login surface lives in its
 * own slice, see PRD #96 for the journey breakdown).
 *
 * <p>Slice 1.1 ships these helpers wired but only exercises {@link #loginAsTenantAdmin}
 * indirectly via the signup journey's redirect target. The {@code data-test-action}
 * attributes on the per-role submit buttons land with the slices that consume them
 * (#98 / #99 / #101).
 */
public final class LoginPageObject {

    private final Page page;
    private final String baseUrl;

    public LoginPageObject(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public void loginAsTenantAdmin(SeededTenant tenant) {
        page.navigate(baseUrl + "/manage/t/" + tenant.slug() + "/login");
        page.getByLabel("Email").fill(tenant.adminEmail());
        page.getByLabel("Password").fill(tenant.adminPassword());
        page.getByTestId("manage-login-submit").click();
        page.waitForURL(baseUrl + "/manage/t/" + tenant.slug() + "/**");
    }

    public void loginAsEndUser(SeededTenant tenant) {
        page.navigate(baseUrl + "/t/" + tenant.slug() + "/login");
        page.getByLabel("Email").fill(tenant.endUserEmail());
        page.getByLabel("Password").fill(tenant.endUserPassword());
        page.getByTestId("login-submit").click();
        // Today every authenticated principal is a TENANT_OWNER, so the
        // terminal /t/{slug}/ landing redirects to /manage/t/{slug}/
        // (issue #283). loginAsEndUserWithCredentials, used by the forced
        // password-change journey, doesn't follow this path — the
        // mustChangePassword intent intercepts before tenantHome().
        page.waitForURL(baseUrl + "/manage/t/" + tenant.slug() + "/**");
    }

    public void loginAsEndUserWithCredentials(String slug, String email, String password) {
        page.navigate(baseUrl + "/t/" + slug + "/login");
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password").fill(password);
        page.getByTestId("login-submit").click();
        page.waitForURL(baseUrl + "/t/" + slug + "/**");
    }

    public void loginAsSystemAdmin(String email, String password) {
        page.navigate(baseUrl + "/manage/t/system/login");
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password").fill(password);
        page.getByTestId("manage-login-submit").click();
        page.waitForURL(baseUrl + "/manage/t/system/**");
    }
}
