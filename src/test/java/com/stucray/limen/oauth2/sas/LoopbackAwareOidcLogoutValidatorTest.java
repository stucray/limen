package com.stucray.limen.oauth2.sas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoopbackAwareOidcLogoutValidator: mirrors SAS /authorize loopback port wildcarding (RFC 8252 §7.3) on /connect/logout")
class LoopbackAwareOidcLogoutValidatorTest {

    private static final Consumer<OidcLogoutAuthenticationContext> ALWAYS_ACCEPT = ctx -> { };
    private static final Consumer<OidcLogoutAuthenticationContext> ALWAYS_REJECT = ctx -> {
        throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST));
    };

    @Nested
    @DisplayName("When the wrapped default validator accepts")
    class WhenDefaultAccepts {

        @Test
        @DisplayName("passes through unchanged (loopback branch is not consulted)")
        void delegateAccepts() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_ACCEPT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("http://example.com/logout"),
                "http://example.com/logout"
            );

            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("When the wrapped default validator rejects, the loopback branch")
    class WhenDefaultRejects {

        @Test
        @DisplayName("accepts an HTTP 127.0.0.1 URI on a different port from the registered one")
        void httpLoopbackPortMismatchAccepted() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("http://127.0.0.1:8080/logout"),
                "http://127.0.0.1:54321/logout"
            );

            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts an HTTPS 127.0.0.1 URI on a different port from the registered one (mirrors SAS — scheme is preserved transitively)")
        void httpsLoopbackPortMismatchAccepted() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("https://127.0.0.1:8443/logout"),
                "https://127.0.0.1:18443/logout"
            );

            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts an IPv6 [::1] URI on a different port from the registered one")
        void ipv6LoopbackPortMismatchAccepted() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("http://[::1]:8080/logout"),
                "http://[::1]:9999/logout"
            );

            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects localhost on a different port (localhost is intentionally not loopback per RFC 8252 §8.3 + SAS #651)")
        void localhostPortMismatchRejected() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("http://localhost:8080/logout"),
                "http://localhost:9999/logout"
            );

            assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthenticationException.class);
        }

        @Test
        @DisplayName("rejects a non-loopback public host with any port mismatch")
        void publicHostPortMismatchRejected() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("https://example.com/logout"),
                "https://example.com:8443/logout"
            );

            assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthenticationException.class);
        }

        @Test
        @DisplayName("rejects when the requested URI has a different scheme from the registered one (port substitution preserves scheme)")
        void schemeMismatchOnLoopbackRejected() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("https://127.0.0.1:8443/logout"),
                "http://127.0.0.1:8443/logout"
            );

            assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthenticationException.class);
        }

        @Test
        @DisplayName("rejects when the requested URI has a different path from the registered one")
        void pathMismatchOnLoopbackRejected() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("http://127.0.0.1:8080/logout"),
                "http://127.0.0.1:9999/different-path"
            );

            assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthenticationException.class);
        }

        @Test
        @DisplayName("rejects when the requested URI is blank — there's nothing to loopback-recover")
        void blankRequestedUriRejected() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                client("http://127.0.0.1:8080/logout"),
                ""
            );

            assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthenticationException.class);
        }

        @Test
        @DisplayName("rejects when the registered client has no post-logout redirect URIs")
        void emptyRegisteredSetRejected() {
            LoopbackAwareOidcLogoutValidator validator = new LoopbackAwareOidcLogoutValidator(ALWAYS_REJECT);
            OidcLogoutAuthenticationContext context = contextOf(
                clientWithNoPostLogoutUris(),
                "http://127.0.0.1:9999/logout"
            );

            assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthenticationException.class);
        }
    }

    private static OidcLogoutAuthenticationContext contextOf(RegisteredClient client, String requestedPostLogoutUri) {
        Authentication principal = new TestingAuthenticationToken("user", "creds");
        principal.setAuthenticated(true);
        OidcLogoutAuthenticationToken token = new OidcLogoutAuthenticationToken(
            "id-token-hint", principal,
            "session-id", client.getClientId(),
            requestedPostLogoutUri, "state"
        );
        return OidcLogoutAuthenticationContext.with(token).registeredClient(client).build();
    }

    private static RegisteredClient client(String postLogoutRedirectUri) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(UUID.randomUUID().toString())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1/cb")
            .postLogoutRedirectUri(postLogoutRedirectUri)
            .scope(OidcScopes.OPENID)
            .build();
    }

    private static RegisteredClient clientWithNoPostLogoutUris() {
        return RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(UUID.randomUUID().toString())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1/cb")
            .scope(OidcScopes.OPENID)
            .build();
    }
}
