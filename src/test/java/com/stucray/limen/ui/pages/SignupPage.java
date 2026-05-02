package com.stucray.limen.ui.pages;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.ManageLoginPage;

public final class SignupPage {

    private final Page page;
    private final String baseUrl;

    public SignupPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public SignupPage fillForm(String organizationName, String slug, String username, String password) {
        page.getByLabel("Organization name").fill(organizationName);
        page.getByLabel("Slug").fill(slug);
        page.getByLabel("Username").fill(username);
        page.getByLabel("Password").fill(password);
        return this;
    }

    public ManageLoginPage submit(String expectedSlug) {
        page.getByTestId("signup-submit").click();
        page.waitForURL(baseUrl + "/manage/t/" + expectedSlug + "/login**");
        return new ManageLoginPage(page, baseUrl, expectedSlug);
    }
}
