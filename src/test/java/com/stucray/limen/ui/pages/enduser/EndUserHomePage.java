package com.stucray.limen.ui.pages.enduser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * The neutral OAuth end-user surface home at {@code /t/{slug}/} (issue #327).
 * It renders a self-contained "you're signed in" page and does NOT bounce to
 * the management console — that bounce was the authorization-scope leak.
 */
public final class EndUserHomePage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public EndUserHomePage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public EndUserHomePage assertOnNeutralHomeForTenant(String tenantDisplayName) {
        page.waitForURL(baseUrl + "/t/" + slug + "/");
        PlaywrightAssertions.assertThat(page.getByTestId("enduser-home")).isVisible();
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("You're signed in"))
        ).isVisible();
        PlaywrightAssertions.assertThat(page.getByText(tenantDisplayName)).isVisible();
        return this;
    }
}
