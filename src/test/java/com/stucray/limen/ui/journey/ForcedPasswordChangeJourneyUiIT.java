package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.enduser.EndUserChangePasswordPage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededForcedChangeUser;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("cross-browser")
@DisplayName("A tenant owner with mustChangePassword set is forced through the change-password page before reaching the management home (issue #283)")
class ForcedPasswordChangeJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: signs in with the temporary password, lands on /t/{slug}/change-password, sets a new password, and is redirected to /manage/t/{slug}/ with their identity visible")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededForcedChangeUser user = tenants.seedEndUserForcedPasswordChange(tenant);

        new LoginPageObject(page, baseUrl())
            .loginAsEndUserWithCredentials(tenant.slug(), user.email(), user.temporaryPassword());

        new EndUserChangePasswordPage(page, baseUrl(), tenant.slug())
            .assertOnChangePasswordPage()
            .fillNewPassword("new-password-123")
            .fillConfirmPassword("new-password-123")
            .submit()
            .assertOnHomeForTenant(tenant.displayName())
            .assertWelcomeForUser(user.email());
    }
}
