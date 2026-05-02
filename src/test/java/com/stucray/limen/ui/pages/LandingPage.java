package com.stucray.limen.ui.pages;

import com.microsoft.playwright.Page;

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
}
