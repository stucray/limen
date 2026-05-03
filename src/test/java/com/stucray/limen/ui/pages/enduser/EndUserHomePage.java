package com.stucray.limen.ui.pages.enduser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class EndUserHomePage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public EndUserHomePage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public EndUserHomePage assertOnHomeForTenant(String tenantDisplayName) {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(tenantDisplayName))
        ).isVisible();
        return this;
    }

    public EndUserHomePage assertSignedInAs(String email) {
        PlaywrightAssertions.assertThat(page.getByText("Signed in as " + email)).isVisible();
        return this;
    }
}
