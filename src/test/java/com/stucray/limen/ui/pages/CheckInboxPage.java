package com.stucray.limen.ui.pages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class CheckInboxPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public CheckInboxPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public CheckInboxPage assertHeading() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Check your inbox"))
        ).isVisible();
        return this;
    }

    public CheckInboxPage assertEmailShown(String expectedEmail) {
        PlaywrightAssertions.assertThat(page.getByTestId("verification-email"))
            .hasText(expectedEmail);
        return this;
    }

    public String slug() {
        return slug;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Page page() {
        return page;
    }
}
