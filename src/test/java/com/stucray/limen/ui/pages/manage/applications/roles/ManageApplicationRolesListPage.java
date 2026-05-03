package com.stucray.limen.ui.pages.manage.applications.roles;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageApplicationRolesListPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageApplicationRolesListPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageApplicationRolesListPage assertOnList() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Roles"))
        ).isVisible();
        return this;
    }

    public ManageApplicationRolesNewPage clickNewRole() {
        page.getByTestId("roles-new").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/applications/*/roles/new");
        return new ManageApplicationRolesNewPage(page, baseUrl, slug);
    }

    public ManageApplicationRolesListPage assertRoleVisible(String roleName) {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(roleName))
        ).isVisible();
        return this;
    }
}
