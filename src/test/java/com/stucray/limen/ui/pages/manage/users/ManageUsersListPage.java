package com.stucray.limen.ui.pages.manage.users;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageUsersListPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageUsersListPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageUsersListPage assertOnList() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Users"))
        ).isVisible();
        return this;
    }

    public ManageUsersNewPage clickAddUser() {
        page.getByTestId("users-new").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/users/new");
        return new ManageUsersNewPage(page, baseUrl, slug);
    }

    public ManageUsersListPage assertUserVisible(String username) {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(username))
        ).isVisible();
        return this;
    }
}
