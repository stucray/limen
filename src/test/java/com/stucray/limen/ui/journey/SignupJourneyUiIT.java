package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.LandingPage;
import com.stucray.limen.ui.support.BaseUiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

@DisplayName("A new tenant administrator can sign up via the landing page")
class SignupJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: lands on /, follows Sign up, submits the form, redirects to the new tenant's login page with a registered banner")
    void happyPath(Page page) {
        String suffix = uniqueSuffix();
        String slug = "t-" + suffix;
        String orgName = "Acme " + suffix;
        String email = "owner-" + suffix + "@example.test";
        String password = "secret123";

        new LandingPage(page, baseUrl())
            .visit()
            .clickSignUp()
            .fillForm(orgName, slug, email, password)
            .submit(slug)
            .assertOnLoginForTenant(orgName)
            .assertJustRegisteredBannerVisible();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
