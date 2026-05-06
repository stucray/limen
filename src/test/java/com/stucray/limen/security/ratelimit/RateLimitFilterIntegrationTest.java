package com.stucray.limen.security.ratelimit;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.provisioning.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RateLimitFilter}. Each rule under test is
 * shrunk via {@link TestPropertySource} to a 2-token bucket with a 1-minute
 * refill, so the third hit in a tight loop is the one we assert against.
 *
 * <p>{@link RateLimitFilter#resetBucketsForTesting()} is invoked in
 * {@code @BeforeEach} so a leak from one test method does not preload the
 * bucket for the next.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "limen.rate-limit.enabled=true",
    "limen.rate-limit.rules.signup-per-ip.path-pattern=/signup",
    "limen.rate-limit.rules.signup-per-ip.key=ip",
    "limen.rate-limit.rules.signup-per-ip.capacity=2",
    "limen.rate-limit.rules.signup-per-ip.refill-tokens=2",
    "limen.rate-limit.rules.signup-per-ip.refill-period=1m",
    "limen.rate-limit.rules.forgot-password-per-ip.path-pattern=/t/[^/]+/forgot-password",
    "limen.rate-limit.rules.forgot-password-per-ip.key=ip",
    "limen.rate-limit.rules.forgot-password-per-ip.capacity=2",
    "limen.rate-limit.rules.forgot-password-per-ip.refill-tokens=2",
    "limen.rate-limit.rules.forgot-password-per-ip.refill-period=1m",
    "limen.rate-limit.rules.oauth2-token-per-ip.path-pattern=(/t/[^/]+)?/oauth2/token",
    "limen.rate-limit.rules.oauth2-token-per-ip.key=ip",
    "limen.rate-limit.rules.oauth2-token-per-ip.capacity=2",
    "limen.rate-limit.rules.oauth2-token-per-ip.refill-tokens=2",
    "limen.rate-limit.rules.oauth2-token-per-ip.refill-period=1m",
    "limen.rate-limit.rules.oauth2-token-per-client.path-pattern=(/t/[^/]+)?/oauth2/token",
    "limen.rate-limit.rules.oauth2-token-per-client.key=client-id",
    "limen.rate-limit.rules.oauth2-token-per-client.capacity=2",
    "limen.rate-limit.rules.oauth2-token-per-client.refill-tokens=2",
    "limen.rate-limit.rules.oauth2-token-per-client.refill-period=1m"
})
@DisplayName("RateLimitFilter throttles pre-auth surfaces and audits 429s")
class RateLimitFilterIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired RateLimitFilter rateLimitFilter;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TenantProvisioningService tenantProvisioningService;

    @BeforeEach
    void resetBucketsAndAudit() {
        rateLimitFilter.resetBucketsForTesting();
        jdbcTemplate.execute("DELETE FROM audit_event WHERE event_type = 'rate_limit_hit'");
        jdbcTemplate.execute(
            "DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
    }

    @Nested
    @DisplayName("/signup")
    class Signup {

        @Test
        @DisplayName("Third POST exceeds capacity=2 and is rejected with 429 + Retry-After")
        void thirdSignupHits429() throws Exception {
            postSignup("acme-1").andExpect(noThrottle());
            postSignup("acme-2").andExpect(noThrottle());

            postSignup("acme-3")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(result -> assertThat(
                    Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                    .isPositive());
        }

        @Test
        @DisplayName("Sub-limit traffic (two POSTs) is not throttled")
        void belowLimitIsNotThrottled() throws Exception {
            postSignup("acme-a").andExpect(noThrottle());
            postSignup("acme-b").andExpect(noThrottle());
        }

        private org.springframework.test.web.servlet.ResultActions postSignup(String slug) throws Exception {
            return mockMvc.perform(post("/signup")
                .param("organizationName", "Acme " + slug)
                .param("slug", slug)
                .param("email", slug + "@example.test")
                .param("password", "secret123")
                .with(csrf()));
        }
    }

    @Nested
    @DisplayName("/t/{slug}/forgot-password")
    class ForgotPassword {

        @Test
        @DisplayName("Third POST exceeds capacity=2 and is rejected with 429 + Retry-After")
        void thirdForgotPasswordHits429() throws Exception {
            postForgotPassword().andExpect(noThrottle());
            postForgotPassword().andExpect(noThrottle());

            postForgotPassword()
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        }

        private org.springframework.test.web.servlet.ResultActions postForgotPassword() throws Exception {
            return mockMvc.perform(post("/t/" + uniqueSlug() + "/forgot-password")
                .param("email", "user@example.test")
                .with(csrf()));
        }
    }

    @Nested
    @DisplayName("/oauth2/token")
    class OAuth2Token {

        @Test
        @DisplayName("Third POST per IP exceeds capacity=2 and is rejected with 429")
        void thirdTokenRequestHits429() throws Exception {
            postToken().andExpect(noThrottle());
            postToken().andExpect(noThrottle());

            postToken()
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        }

        private org.springframework.test.web.servlet.ResultActions postToken() throws Exception {
            return mockMvc.perform(post("/oauth2/token")
                .param("grant_type", "client_credentials")
                .with(csrf()));
        }
    }

    @Nested
    @DisplayName("audit emit")
    class Audit {

        @Test
        @DisplayName("A 429 hit publishes RateLimitHitEvent and writes exactly one audit_event row")
        void rateLimitHitProducesExactlyOneAuditRow() throws Exception {
            // Burn the bucket and capture the rejection.
            mockMvc.perform(post("/signup")
                .param("organizationName", "X").param("slug", "x-1")
                .param("email", "x1@example.test").param("password", "secret123")
                .with(csrf()));
            mockMvc.perform(post("/signup")
                .param("organizationName", "X").param("slug", "x-2")
                .param("email", "x2@example.test").param("password", "secret123")
                .with(csrf()));
            mockMvc.perform(post("/signup")
                .param("organizationName", "X").param("slug", "x-3")
                .param("email", "x3@example.test").param("password", "secret123")
                .with(csrf()))
                .andExpect(status().isTooManyRequests());

            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'rate_limit_hit'",
                Integer.class);
            assertThat(count).isEqualTo(1);

            // Postgres jsonb text-rendering inserts a space after ':' on read,
            // so we cast to ->>'<key>' for an unambiguous string comparison.
            String ruleId = jdbcTemplate.queryForObject(
                "SELECT details->>'ruleId' FROM audit_event WHERE event_type = 'rate_limit_hit'",
                String.class);
            String path = jdbcTemplate.queryForObject(
                "SELECT details->>'path' FROM audit_event WHERE event_type = 'rate_limit_hit'",
                String.class);
            String method = jdbcTemplate.queryForObject(
                "SELECT details->>'method' FROM audit_event WHERE event_type = 'rate_limit_hit'",
                String.class);
            assertThat(ruleId).isEqualTo("signup-per-ip");
            assertThat(path).isEqualTo("/signup");
            assertThat(method).isEqualTo("POST");
        }

        @Test
        @DisplayName("Sub-limit traffic writes no rate_limit_hit rows")
        void belowLimitProducesNoAuditRows() throws Exception {
            mockMvc.perform(post("/signup")
                .param("organizationName", "Y").param("slug", "y-1")
                .param("email", "y1@example.test").param("password", "secret123")
                .with(csrf()));

            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'rate_limit_hit'",
                Integer.class);
            assertThat(count).isZero();
        }
    }

    private static String uniqueSlug() {
        return "fp-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static org.springframework.test.web.servlet.ResultMatcher noThrottle() {
        return result -> assertThat(result.getResponse().getStatus())
            .as("should not be throttled (got %d)", result.getResponse().getStatus())
            .isNotEqualTo(429);
    }
}
