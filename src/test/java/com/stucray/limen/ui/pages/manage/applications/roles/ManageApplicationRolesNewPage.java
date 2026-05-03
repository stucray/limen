package com.stucray.limen.ui.pages.manage.applications.roles;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageApplicationRolesNewPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageApplicationRolesNewPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageApplicationRolesNewPage assertOnForm() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("New role"))
        ).isVisible();
        return this;
    }

    public ManageApplicationRolesNewPage fillName(String name) {
        page.getByLabel("Name").fill(name);
        return this;
    }

    public ManageApplicationRolesNewPage fillDescription(String description) {
        page.getByLabel("Description").fill(description);
        return this;
    }

    public ManageApplicationRolesListPage submit() {
        page.getByTestId("roles-create-submit").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/applications/*/roles");
        return new ManageApplicationRolesListPage(page, baseUrl, slug);
    }
}
