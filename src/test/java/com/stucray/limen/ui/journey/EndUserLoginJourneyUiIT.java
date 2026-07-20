package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.enduser.EndUserHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("cross-browser")
@DisplayName("A user signing in at /t/{slug}/login lands on the neutral end-user home, NOT the management console (issue #327)")
class EndUserLoginJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: signs in at /t/{slug}/login and lands on the neutral /t/{slug}/ home — the end-user surface no longer bounces to /manage/t/{slug}/")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();

        new LoginPageObject(page, baseUrl()).loginAsEndUser(tenant);

        new EndUserHomePage(page, baseUrl(), tenant.slug())
            .assertOnNeutralHomeForTenant(tenant.displayName());
    }
}
