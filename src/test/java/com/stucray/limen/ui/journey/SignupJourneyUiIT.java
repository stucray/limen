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
        String username = "owner-" + suffix;
        String password = "secret123";

        new LandingPage(page, baseUrl())
            .visit()
            .clickSignUp()
            .fillForm(orgName, slug, username, password)
            .submit(slug)
            .assertOnLoginForTenant(orgName)
            .assertJustRegisteredBannerVisible();

        // INTENTIONAL FAILURE — verifying Playwright trace + screenshot artifact
        // upload on the CI failure path. Reverted in the next commit.
        throw new AssertionError("intentional failure to exercise CI artifact upload");
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
