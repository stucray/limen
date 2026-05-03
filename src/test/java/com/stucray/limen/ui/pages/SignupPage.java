package com.stucray.limen.ui.pages;

import com.microsoft.playwright.Page;

public final class SignupPage {

    private final Page page;
    private final String baseUrl;

    public SignupPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public SignupPage fillForm(String organizationName, String slug, String email, String password) {
        page.getByLabel("Organization name").fill(organizationName);
        page.getByLabel("Slug").fill(slug);
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password").fill(password);
        return this;
    }

    public CheckInboxPage submit(String expectedSlug) {
        page.getByTestId("signup-submit").click();
        page.waitForURL(baseUrl + "/t/" + expectedSlug + "/check-inbox**");
        return new CheckInboxPage(page, baseUrl, expectedSlug);
    }
}
