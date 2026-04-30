package com.stucray.limen.auth.login;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantUrlSchemeUnitTest {

    private static final TenantUrlScheme OAUTH2 = new TenantUrlScheme(
        "oauth2",
        HttpMethod.POST, "/t/*/login",
        Pattern.compile("^/t/([^/]+)(?:/.*)?$"),
        "/t/{slug}/login",
        "/t/{slug}/",
        "/t/{slug}/change-password"
    );

    private static final TenantUrlScheme MANAGEMENT = new TenantUrlScheme(
        "management",
        HttpMethod.POST, "/manage/t/*/login",
        Pattern.compile("^/manage/t/([^/]+)(?:/.*)?$"),
        "/manage/t/{slug}/login",
        "/manage/t/{slug}/",
        "/manage/t/{slug}/change-password"
    );

    @Test
    void slugFromHappyPathOAuth2Login() {
        assertThat(OAUTH2.slugFrom("/t/alpha/login")).isEqualTo("alpha");
    }

    @Test
    void slugFromHappyPathManagementLogin() {
        assertThat(MANAGEMENT.slugFrom("/manage/t/alpha/login")).isEqualTo("alpha");
    }

    @Test
    void slugFromAcceptsBaseTenantUrlWithoutTrailingSegment() {
        assertThat(OAUTH2.slugFrom("/t/alpha")).isEqualTo("alpha");
    }

    @Test
    void slugFromReturnsNullWhenUriDoesNotMatchScheme() {
        assertThat(OAUTH2.slugFrom("/manage/t/alpha/login")).isNull();
        assertThat(MANAGEMENT.slugFrom("/t/alpha/login")).isNull();
    }

    @Test
    void slugFromReturnsNullForMalformedUri() {
        // No /t/ prefix, no slug capture.
        assertThat(OAUTH2.slugFrom("/some/random/path")).isNull();
    }

    @Test
    void slugFromReturnsNullForEmptySlug() {
        // Pattern uses [^/]+ which requires at least one char, so // collapses to no match.
        assertThat(OAUTH2.slugFrom("/t//login")).isNull();
    }

    @Test
    void slugFromReturnsNullForNullUri() {
        assertThat(OAUTH2.slugFrom((String) null)).isNull();
    }

    @Test
    void slugFromAcceptsSlugWithSpecialCharacters() {
        assertThat(OAUTH2.slugFrom("/t/acme-corp.io/login")).isEqualTo("acme-corp.io");
    }

    @Test
    void loginUrlRendersTemplate() {
        assertThat(OAUTH2.loginUrl("alpha")).isEqualTo("/t/alpha/login");
        assertThat(MANAGEMENT.loginUrl("alpha")).isEqualTo("/manage/t/alpha/login");
    }

    @Test
    void homeUrlRendersTemplate() {
        assertThat(OAUTH2.homeUrl("alpha")).isEqualTo("/t/alpha/");
        assertThat(MANAGEMENT.homeUrl("alpha")).isEqualTo("/manage/t/alpha/");
    }

    @Test
    void changePasswordUrlRendersTemplate() {
        assertThat(OAUTH2.changePasswordUrl("alpha")).isEqualTo("/t/alpha/change-password");
        assertThat(MANAGEMENT.changePasswordUrl("alpha")).isEqualTo("/manage/t/alpha/change-password");
    }

    @Test
    void loginUrlRejectsEmptySlug() {
        assertThatThrownBy(() -> OAUTH2.loginUrl(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void homeUrlRejectsNullSlug() {
        assertThatThrownBy(() -> OAUTH2.homeUrl(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changePasswordUrlRejectsEmptySlug() {
        assertThatThrownBy(() -> OAUTH2.changePasswordUrl(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
