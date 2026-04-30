package com.stucray.limen.management;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SignupIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
    }

    @Test
    void signupFormRendersForUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/signup"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"slug\"")));
    }

    @Test
    void successfulSignupCreatesTenantAndOwner() throws Exception {
        mockMvc.perform(post("/signup")
                .param("organizationName", "Acme Corp")
                .param("slug", "acme-corp")
                .param("username", "alice")
                .param("password", "secret123")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/acme-corp/login?registered"));

        assertThat(tenantRepository.findBySlug("acme-corp")).isPresent();
        Integer userCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users u JOIN tenants t ON u.tenant_id = t.id WHERE t.slug = 'acme-corp'",
            Integer.class);
        assertThat(userCount).isEqualTo(1);
    }

    @Test
    void duplicateSlugReturnsError() throws Exception {
        jdbcTemplate.execute(
            "INSERT INTO tenants (slug, display_name, status) VALUES ('taken-slug', 'Taken', 'ACTIVE')");

        mockMvc.perform(post("/signup")
                .param("organizationName", "Another Corp")
                .param("slug", "taken-slug")
                .param("username", "bob")
                .param("password", "secret123")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("already taken")));
    }

    @Test
    void reservedSlugReturnsError() throws Exception {
        for (String reserved : new String[]{"system", "admin", "manage", "api", "www", "static", "health", "limen"}) {
            mockMvc.perform(post("/signup")
                    .param("organizationName", "Test")
                    .param("slug", reserved)
                    .param("username", "user")
                    .param("password", "secret123")
                    .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reserved")));
        }
    }

    @Test
    void invalidSlugFormatReturnsError() throws Exception {
        mockMvc.perform(post("/signup")
                .param("organizationName", "Test")
                .param("slug", "UPPERCASE")
                .param("username", "user")
                .param("password", "secret123")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Slug may only contain")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFormCases")
    void invalidFormFieldRendersFieldError(
        String label, String slug, String orgName, String username, String password, String expectedFragment
    ) throws Exception {
        mockMvc.perform(post("/signup")
                .param("slug", slug)
                .param("organizationName", orgName)
                .param("username", username)
                .param("password", password)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(expectedFragment)));
    }

    static java.util.stream.Stream<Arguments> invalidFormCases() {
        String fortyNineChars  = "a".repeat(49);
        String hundredOneChars = "u".repeat(101);
        return java.util.stream.Stream.of(
            Arguments.of("slug too short",          "ab",            "Acme", "alice", "secret123", "between 3 and 48"),
            Arguments.of("slug too long",           fortyNineChars,  "Acme", "alice", "secret123", "between 3 and 48"),
            Arguments.of("blank organization name", "acme-corp",     "",     "alice", "secret123", "Organization name is required"),
            Arguments.of("blank username",          "acme-corp",     "Acme", "",      "secret123", "Username is required"),
            Arguments.of("username too long",       "acme-corp",     "Acme", hundredOneChars, "secret123", "100 characters or fewer"),
            Arguments.of("blank password",          "acme-corp",     "Acme", "alice", "",          "Password is required")
        );
    }
}
