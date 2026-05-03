package com.stucray.limen.ui.pages.manage.applications.clients;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

public final class ManageApplicationClientsNewPage {

    private final Page page;
    private final String baseUrl;
    private final String slug;

    public ManageApplicationClientsNewPage(Page page, String baseUrl, String slug) {
        this.page = page;
        this.baseUrl = baseUrl;
        this.slug = slug;
    }

    public ManageApplicationClientsNewPage assertOnForm() {
        PlaywrightAssertions.assertThat(
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("New client"))
        ).isVisible();
        return this;
    }

    public ManageApplicationClientsNewPage fillName(String displayName) {
        page.getByLabel("Name").fill(displayName);
        return this;
    }

    public ManageApplicationClientsNewPage checkClientCredentialsGrant() {
        page.getByLabel("Client credentials").check();
        return this;
    }

    public ManageApplicationClientsListPage submit() {
        page.getByTestId("clients-create-submit").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/applications/*/clients");
        return new ManageApplicationClientsListPage(page, baseUrl, slug);
    }
}
