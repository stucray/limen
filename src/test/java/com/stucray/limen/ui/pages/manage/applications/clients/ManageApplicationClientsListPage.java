package com.stucray.limen.ui.pages.manage.applications.clients;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageApplicationClientsListPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageApplicationClientsListPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageApplicationClientsListPage assertOnList() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Clients"))
        ).isVisible();
        return this;
    }

    public ManageApplicationClientsNewPage clickNewClient() {
        page.getByTestId("clients-new").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/applications/*/clients/new");
        return new ManageApplicationClientsNewPage(page, baseUrl, slug);
    }

    public ManageApplicationClientsListPage assertClientVisible(String displayName) {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(displayName))
        ).isVisible();
        return this;
    }
}
