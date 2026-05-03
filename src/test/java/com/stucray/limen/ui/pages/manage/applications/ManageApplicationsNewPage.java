package com.stucray.limen.ui.pages.manage.applications;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageApplicationsNewPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageApplicationsNewPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageApplicationsNewPage assertOnForm() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("New application"))
        ).isVisible();
        return this;
    }

    public ManageApplicationsNewPage fillName(String name) {
        page.getByLabel("Name").fill(name);
        return this;
    }

    public ManageApplicationsNewPage fillDescription(String description) {
        page.getByLabel("Description").fill(description);
        return this;
    }

    public ManageApplicationsListPage submit() {
        page.getByTestId("apps-create-submit").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/applications");
        return new ManageApplicationsListPage(page, baseUrl, slug);
    }
}
