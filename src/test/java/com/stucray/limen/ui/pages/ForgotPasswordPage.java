package com.stucray.limen.ui.pages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ForgotPasswordPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ForgotPasswordPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ForgotPasswordPage assertHeading() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Reset your password"))
        ).isVisible();
        return this;
    }

    public ForgotPasswordPage fillEmail(String email) {
        page.getByLabel("Email").fill(email);
        return this;
    }

    public CheckInboxPage submit() {
        page.getByTestId("forgot-password-submit").click();
        page.waitForURL("**/t/" + slug + "/check-inbox**");
        return new CheckInboxPage(page, baseUrl, slug);
    }
}
