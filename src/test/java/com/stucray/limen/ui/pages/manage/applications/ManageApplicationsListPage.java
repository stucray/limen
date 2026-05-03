package com.stucray.limen.ui.pages.manage.applications;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageApplicationsListPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageApplicationsListPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageApplicationsListPage assertOnList() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Applications"))
        ).isVisible();
        return this;
    }

    public ManageApplicationsNewPage clickNewApplication() {
        page.getByTestId("apps-new").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/applications/new");
        return new ManageApplicationsNewPage(page, baseUrl, slug);
    }

    public ManageApplicationsListPage assertApplicationVisible(String applicationName) {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(applicationName))
        ).isVisible();
        return this;
    }
}
