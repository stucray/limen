package com.stucray.limen.ui.pages.manage.system;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class SystemTenantsListPage {

    private final Page page;
    private final String baseUrl;

    public SystemTenantsListPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public SystemTenantsListPage visit() {
        page.navigate(baseUrl + "/manage/system/tenants");
        return this;
    }

    public SystemTenantsListPage assertOnTenantsList() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Tenants"))
        ).isVisible();
        return this;
    }

    public SystemTenantsListPage assertTenantSlugVisible(String slug) {
        // setExact(true) targets the <code>{slug}</code> cell directly. The
        // post-create flash message ("Created tenant {slug}.") would otherwise
        // also match and trip Playwright's strict-mode duplicate guard.
        PlaywrightAssertions.assertThat(
            page.getByText(slug, new Page.GetByTextOptions().setExact(true))
        ).isVisible();
        return this;
    }
}
