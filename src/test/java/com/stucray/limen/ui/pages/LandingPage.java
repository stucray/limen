package com.stucray.limen.ui.pages;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.ManageLoginPage;

public final class LandingPage {

    private final Page page;
    private final String baseUrl;

    public LandingPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public LandingPage visit() {
        page.navigate(baseUrl + "/");
        return this;
    }

    public SignupPage clickSignUp() {
        page.getByTestId("landing-signup").click();
        return new SignupPage(page, baseUrl);
    }

    public LandingPage fillSlug(String slug) {
        page.getByLabel("Organization slug").fill(slug);
        return this;
    }

    public ManageLoginPage clickContinue(String slug) {
        page.getByTestId("landing-continue").click();
        page.waitForURL(baseUrl + "/manage/t/" + slug + "/login");
        return new ManageLoginPage(page, baseUrl, slug);
    }
}
