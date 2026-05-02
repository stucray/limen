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
        PlaywrightAssertions.assertThat(page.getByText(slug)).isVisible();
        return this;
    }
}
