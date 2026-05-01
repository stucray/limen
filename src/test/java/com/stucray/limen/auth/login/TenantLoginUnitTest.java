package com.stucray.limen.auth.login;

import com.stucray.limen.auth.TenantPersistentTokenBasedRememberMeServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantLogin (immutable wither chain + intent ordering)")
class TenantLoginUnitTest {

    @Mock AuthenticationManager authManager;
    @Mock TenantPersistentTokenBasedRememberMeServices rememberMe;

    PostLoginIntent intentA;
    PostLoginIntent intentB;
    PostLoginIntent intentC;
    TenantLogin original;

    @BeforeEach
    void setUp() {
        intentA = (req, res, p, scheme) -> null;
        intentB = (req, res, p, scheme) -> null;
        intentC = (req, res, p, scheme) -> null;
        original = new TenantLogin(authManager, rememberMe, "key", List.of(intentA, intentB), List.of());
    }

    @Test
    @DisplayName("withIntents() returns a new instance and leaves the original's intent list intact")
    void withIntentsReturnsNewInstanceWithoutMutatingOriginal() {
        TenantLogin replaced = original.withIntents(List.of(intentC));

        assertThat(replaced).isNotSameAs(original);
        assertThat(original.intents()).containsExactly(intentA, intentB);
        assertThat(replaced.intents()).containsExactly(intentC);
    }

    @Test
    @DisplayName("withRememberMe() returns a new instance and leaves the original's flag intact")
    void withRememberMeReturnsNewInstanceWithoutMutatingOriginal() {
        TenantLogin disabled = original.withRememberMe(false);

        assertThat(disabled).isNotSameAs(original);
        assertThat(original.rememberMeEnabled()).isTrue();
        assertThat(disabled.rememberMeEnabled()).isFalse();
    }

    @Test
    @DisplayName("withRememberMe() and withIntents() can be chained and compose without mutating the original")
    void withMethodsAreChainable() {
        TenantLogin chained = original.withRememberMe(false).withIntents(List.of(intentC));

        assertThat(chained.rememberMeEnabled()).isFalse();
        assertThat(chained.intents()).containsExactly(intentC);
        assertThat(original.rememberMeEnabled()).isTrue();
        assertThat(original.intents()).containsExactly(intentA, intentB);
    }

    @Test
    @DisplayName("Two intents with the same explicit @Order value blow up at startup")
    void verifyOrderUniquenessRejectsDuplicateExplicitOrders() throws Exception {
        @Order(50) class IntentX implements PostLoginIntent {
            public String resolve(jakarta.servlet.http.HttpServletRequest req,
                                  jakarta.servlet.http.HttpServletResponse res,
                                  com.stucray.limen.auth.TenantUserDetails p,
                                  TenantUrlScheme scheme) { return null; }
        }
        @Order(50) class IntentY implements PostLoginIntent {
            public String resolve(jakarta.servlet.http.HttpServletRequest req,
                                  jakarta.servlet.http.HttpServletResponse res,
                                  com.stucray.limen.auth.TenantUserDetails p,
                                  TenantUrlScheme scheme) { return null; }
        }
        TenantLogin login = new TenantLogin(
            authManager, rememberMe, "key", List.of(new IntentX(), new IntentY()), List.of());

        Method post = TenantLogin.class.getDeclaredMethod("verifyOrderUniqueness");
        post.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                post.invoke(login);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        })
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate @Order(50)");
    }

    @Test
    @DisplayName("Distinct explicit @Order values are accepted")
    void verifyOrderUniquenessAllowsDistinctExplicitOrders() throws Exception {
        @Order(10) class IntentX implements PostLoginIntent {
            public String resolve(jakarta.servlet.http.HttpServletRequest req,
                                  jakarta.servlet.http.HttpServletResponse res,
                                  com.stucray.limen.auth.TenantUserDetails p,
                                  TenantUrlScheme scheme) { return null; }
        }
        @Order(20) class IntentY implements PostLoginIntent {
            public String resolve(jakarta.servlet.http.HttpServletRequest req,
                                  jakarta.servlet.http.HttpServletResponse res,
                                  com.stucray.limen.auth.TenantUserDetails p,
                                  TenantUrlScheme scheme) { return null; }
        }
        TenantLogin login = new TenantLogin(
            authManager, rememberMe, "key", List.of(new IntentX(), new IntentY()), List.of());

        Method post = TenantLogin.class.getDeclaredMethod("verifyOrderUniqueness");
        post.setAccessible(true);
        post.invoke(login); // does not throw
    }

    @Test
    @DisplayName("Intents without an explicit @Order are not subject to the uniqueness check")
    void verifyOrderUniquenessIgnoresIntentsWithoutOrder() throws Exception {
        // Two lambda intents have no @Order annotation; default chain mixes them with no collision.
        TenantLogin login = new TenantLogin(authManager, rememberMe, "key",
            List.of(intentA, intentB, intentC), List.of());

        Method post = TenantLogin.class.getDeclaredMethod("verifyOrderUniqueness");
        post.setAccessible(true);
        post.invoke(login); // does not throw
    }
}
