package com.stucray.limen.ui.pages.manage;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageLoginPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageLoginPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageLoginPage visit() {
        page.navigate(baseUrl + "/manage/t/" + slug + "/login");
        return this;
    }

    public ManageLoginPage assertOnLoginForTenant(String expectedDisplayName) {
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign in"))).isVisible();
        PlaywrightAssertions.assertThat(page.getByText(expectedDisplayName)).isVisible();
        return this;
    }

    public ManageLoginPage assertJustRegisteredBannerVisible() {
        PlaywrightAssertions.assertThat(page.getByText("Account created — please sign in.")).isVisible();
        return this;
    }

    public ManageLoginPage fill(String email, String password) {
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password").fill(password);
        return this;
    }

    public void submit() {
        page.getByTestId("manage-login-submit").click();
    }
}
