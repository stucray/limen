package com.stucray.limen.ui.pages.enduser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class EndUserChangePasswordPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public EndUserChangePasswordPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public EndUserChangePasswordPage assertOnChangePasswordPage() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Change your password"))
        ).isVisible();
        return this;
    }

    public EndUserChangePasswordPage fillNewPassword(String newPassword) {
        page.getByLabel("New password").fill(newPassword);
        return this;
    }

    public EndUserChangePasswordPage fillConfirmPassword(String confirmPassword) {
        page.getByLabel("Confirm password").fill(confirmPassword);
        return this;
    }

    public EndUserHomePage submit() {
        page.getByTestId("change-password-submit").click();
        page.waitForURL(baseUrl + "/t/" + slug + "/");
        return new EndUserHomePage(page, baseUrl, slug);
    }
}
