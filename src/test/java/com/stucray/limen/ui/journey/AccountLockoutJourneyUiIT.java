package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.stucray.limen.auth.lockout.LockoutProperties;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.TestTenantFactory;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright UI journey for slice 6: an end-user mis-types five times and gets
 * locked out, the locked-error message renders on the next login attempt, the
 * tenant admin unlocks them via the user-detail page, and the end-user signs
 * in successfully.
 *
 * <p>Seeded by {@link TestTenantFactory#createTenant} which provides an
 * already-verified tenant admin and an end-user — we lock the end-user, then
 * use the admin to unlock them. Two principals + the same tenant covers the
 * "admin acts on another user's row" path the spec calls for.
 */
@DisplayName("Account lockout journey: 5 wrong passwords lock the user, the locked-error renders, admin unlocks via UI, user signs in")
class AccountLockoutJourneyUiIT extends BaseUiIT {

    @Autowired LockoutProperties lockoutProperties;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("happy path: end-user fat-fingers 5 times → locked banner → admin unlock → end-user signs in with correct password")
    void happyPath(Page page) {
        TestTenantFactory.SeededTenant tenant = tenants.createTenant();
        String slug = tenant.slug();
        String endUserEmail = tenant.endUserEmail();
        String endUserPassword = tenant.endUserPassword();

        // 1. End-user mis-types `threshold` times — each attempt redirects with
        //    a generic ?error=1; we don't yet care about the in-page message.
        int threshold = lockoutProperties.threshold();
        for (int i = 0; i < threshold; i++) {
            page.navigate(baseUrl() + "/t/" + slug + "/login");
            page.getByLabel("Email").fill(endUserEmail);
            page.getByLabel("Password").fill("wrong-" + i);
            page.getByTestId("login-submit").click();
            page.waitForURL("**/t/" + slug + "/login**");
        }

        // 2. The right password now bounces with the locked-specific banner —
        //    proves the pre-auth gate fires before password verification.
        page.navigate(baseUrl() + "/t/" + slug + "/login");
        page.getByLabel("Email").fill(endUserEmail);
        page.getByLabel("Password").fill(endUserPassword);
        page.getByTestId("login-submit").click();
        page.waitForURL("**/t/" + slug + "/login?error=locked");
        PlaywrightAssertions.assertThat(page.getByTestId("login-error-locked")).isVisible();

        // 3. Admin signs in to /manage and visits the locked user's detail page.
        Long endUserId = userRepository.findByEmailAndTenantId(endUserEmail, tenant.tenantId())
            .map(User::id)
            .orElseThrow();
        page.context().clearCookies();
        page.navigate(baseUrl() + "/manage/t/" + slug + "/login");
        page.getByLabel("Email").fill(tenant.adminEmail());
        page.getByLabel("Password").fill(tenant.adminPassword());
        page.getByTestId("manage-login-submit").click();
        page.waitForURL(baseUrl() + "/manage/t/" + slug + "/");

        page.navigate(baseUrl() + "/manage/t/" + slug + "/users/" + endUserId);
        // Lockout badge proves the read path renders the state correctly.
        PlaywrightAssertions.assertThat(page.getByTestId("user-lockout-status")).containsText("Locked until");
        page.getByTestId("user-unlock-submit").click();
        page.waitForURL(baseUrl() + "/manage/t/" + slug + "/users/" + endUserId);
        PlaywrightAssertions.assertThat(page.getByTestId("user-lockout-status")).hasText("Not locked");

        // 4. End-user signs back in with the correct password — the unlock
        //    cleared the gate, so the password check runs and succeeds.
        //    Post-login terminal redirect /t/{slug}/ bounces to the management
        //    home for owners (issue #283).
        page.context().clearCookies();
        page.navigate(baseUrl() + "/t/" + slug + "/login");
        page.getByLabel("Email").fill(endUserEmail);
        page.getByLabel("Password").fill(endUserPassword);
        page.getByTestId("login-submit").click();
        page.waitForURL(baseUrl() + "/manage/t/" + slug + "/");

        // Persistence sanity: row state matches what the UI reported.
        User after = userRepository.findById(endUserId).orElseThrow();
        assertThat(after.failedLoginAttempts()).isZero();
        assertThat(after.lockedUntil()).isNull();
    }
}
