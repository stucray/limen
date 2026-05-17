package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.LandingPage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("cross-browser")
@DisplayName("A visitor entering a tenant slug on the landing page is forwarded to that tenant's login")
class LandingForwarderJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: typing a slug at / and submitting lands on /manage/t/{slug}/login with the tenant display name visible")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();

        new LandingPage(page, baseUrl())
            .visit()
            .fillSlug(tenant.slug())
            .clickContinue(tenant.slug())
            .assertOnLoginForTenant(tenant.displayName());
    }
}
