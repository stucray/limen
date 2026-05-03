package com.stucray.limen.ui.pages.manage;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.stucray.limen.ui.pages.manage.applications.ManageApplicationsListPage;
import com.stucray.limen.ui.pages.manage.users.ManageUsersListPage;

public final class ManageHomePage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageHomePage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageApplicationsListPage clickApplications() {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Applications")).click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/applications");
        return new ManageApplicationsListPage(page, baseUrl, slug);
    }

    public ManageUsersListPage clickUsers() {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Users")).click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/users");
        return new ManageUsersListPage(page, baseUrl, slug);
    }

    public ManageHomePage assertOnHomeForTenant(String tenantDisplayName) {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(tenantDisplayName))
        ).isVisible();
        return this;
    }

    public ManageHomePage assertWelcomeForUser(String email) {
        PlaywrightAssertions.assertThat(page.getByText("Welcome, " + email)).isVisible();
        return this;
    }

    public ManageHomePage assertTenantAdminNavTilesVisible() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Users"))
        ).isVisible();
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Applications"))
        ).isVisible();
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Tenant Settings"))
        ).isVisible();
        return this;
    }
}
