package com.stucray.limen.ui.pages.manage.users;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageUsersNewPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageUsersNewPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageUsersNewPage assertOnForm() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Add user"))
        ).isVisible();
        return this;
    }

    public ManageUsersNewPage fillEmail(String email) {
        page.getByLabel("Email").fill(email);
        return this;
    }

    public ManageUsersNewPage fillTemporaryPassword(String temporaryPassword) {
        page.getByLabel("Temporary password").fill(temporaryPassword);
        return this;
    }

    public ManageUsersListPage submit() {
        page.getByTestId("users-create-submit").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/users");
        return new ManageUsersListPage(page, baseUrl, slug);
    }
}
