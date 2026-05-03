package com.stucray.limen.ui.pages.manage.system;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class SystemTenantCreatePage {

    private final Page page;
    private final String baseUrl;

    public SystemTenantCreatePage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public SystemTenantCreatePage visitFromTenantsList() {
        page.getByTestId("new-tenant").click();
        page.waitForURL(baseUrl + "/manage/system/tenants/new");
        return this;
    }

    public SystemTenantCreatePage assertOnForm() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Create a tenant"))
        ).isVisible();
        return this;
    }

    public SystemTenantCreatePage fillForm(String slug, String displayName, String ownerEmail) {
        page.locator("[data-test-field='slug']").fill(slug);
        page.locator("[data-test-field='displayName']").fill(displayName);
        page.locator("[data-test-field='ownerEmail']").fill(ownerEmail);
        return this;
    }

    public SystemTenantsListPage submit() {
        page.getByTestId("tenant-create-submit").click();
        page.waitForURL(baseUrl + "/manage/system/tenants");
        return new SystemTenantsListPage(page, baseUrl);
    }
}
