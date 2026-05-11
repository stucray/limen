package com.stucray.limen.auth.login;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantUrlScheme: slug parsing + URL templating")
class TenantUrlSchemeUnitTest {

    private static final TenantUrlScheme OAUTH2 = new TenantUrlScheme(
        "oauth2",
        HttpMethod.POST, "/t/*/login",
        Pattern.compile("^/t/([^/]+)(?:/.*)?$"),
        "/t/{slug}/login",
        "/t/{slug}/",
        "/t/{slug}/change-password",
        "/t/*/logout",
        LogoutSlugSource.REQUEST_URI,
        "/"
    );

    private static final TenantUrlScheme MANAGEMENT = new TenantUrlScheme(
        "management",
        HttpMethod.POST, "/manage/t/*/login",
        Pattern.compile("^/manage/t/([^/]+)(?:/.*)?$"),
        "/manage/t/{slug}/login",
        "/manage/t/{slug}/",
        "/manage/t/{slug}/change-password",
        "/manage/logout",
        LogoutSlugSource.REFERER_HEADER,
        "/manage/t/system/login"
    );

    @Test
    @DisplayName("slugFrom: extracts the slug from /t/{slug}/login on the OAuth2 surface")
    void slugFromHappyPathOAuth2Login() {
        assertThat(OAUTH2.slugFrom("/t/alpha/login")).isEqualTo("alpha");
    }

    @Test
    @DisplayName("slugFrom: extracts the slug from /manage/t/{slug}/login on the management surface")
    void slugFromHappyPathManagementLogin() {
        assertThat(MANAGEMENT.slugFrom("/manage/t/alpha/login")).isEqualTo("alpha");
    }

    @Test
    @DisplayName("slugFrom: extracts the slug from /t/{slug} (no trailing segment)")
    void slugFromAcceptsBaseTenantUrlWithoutTrailingSegment() {
        assertThat(OAUTH2.slugFrom("/t/alpha")).isEqualTo("alpha");
    }

    @Test
    @DisplayName("slugFrom: returns null when the URI is for a different surface (no cross-scheme matching)")
    void slugFromReturnsNullWhenUriDoesNotMatchScheme() {
        assertThat(OAUTH2.slugFrom("/manage/t/alpha/login")).isNull();
        assertThat(MANAGEMENT.slugFrom("/t/alpha/login")).isNull();
    }

    @Test
    @DisplayName("slugFrom: returns null when the URI has no /t/ prefix at all")
    void slugFromReturnsNullForMalformedUri() {
        // No /t/ prefix, no slug capture.
        assertThat(OAUTH2.slugFrom("/some/random/path")).isNull();
    }

    @Test
    @DisplayName("slugFrom: returns null when the slug segment is empty (//)")
    void slugFromReturnsNullForEmptySlug() {
        // Pattern uses [^/]+ which requires at least one char, so // collapses to no match.
        assertThat(OAUTH2.slugFrom("/t//login")).isNull();
    }

    @Test
    @DisplayName("slugFrom: returns null when the URI is null")
    void slugFromReturnsNullForNullUri() {
        assertThat(OAUTH2.slugFrom((String) null)).isNull();
    }

    @Test
    @DisplayName("slugFrom: accepts hyphens and dots inside the slug (e.g. acme-corp.io)")
    void slugFromAcceptsSlugWithSpecialCharacters() {
        assertThat(OAUTH2.slugFrom("/t/acme-corp.io/login")).isEqualTo("acme-corp.io");
    }

    @Test
    @DisplayName("loginUrl: renders the login URL template for both surfaces")
    void loginUrlRendersTemplate() {
        assertThat(OAUTH2.loginUrl("alpha")).isEqualTo("/t/alpha/login");
        assertThat(MANAGEMENT.loginUrl("alpha")).isEqualTo("/manage/t/alpha/login");
    }

    @Test
    @DisplayName("homeUrl: renders the post-login home URL template for both surfaces")
    void homeUrlRendersTemplate() {
        assertThat(OAUTH2.homeUrl("alpha")).isEqualTo("/t/alpha/");
        assertThat(MANAGEMENT.homeUrl("alpha")).isEqualTo("/manage/t/alpha/");
    }

    @Test
    @DisplayName("changePasswordUrl: renders the change-password URL template for both surfaces")
    void changePasswordUrlRendersTemplate() {
        assertThat(OAUTH2.changePasswordUrl("alpha")).isEqualTo("/t/alpha/change-password");
        assertThat(MANAGEMENT.changePasswordUrl("alpha")).isEqualTo("/manage/t/alpha/change-password");
    }

    @Test
    @DisplayName("loginUrl: rejects an empty slug")
    void loginUrlRejectsEmptySlug() {
        assertThatThrownBy(() -> OAUTH2.loginUrl(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("homeUrl: rejects a null slug")
    void homeUrlRejectsNullSlug() {
        assertThatThrownBy(() -> OAUTH2.homeUrl(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("changePasswordUrl: rejects an empty slug")
    void changePasswordUrlRejectsEmptySlug() {
        assertThatThrownBy(() -> OAUTH2.changePasswordUrl(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
