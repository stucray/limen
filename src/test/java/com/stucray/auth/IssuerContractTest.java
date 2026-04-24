package com.stucray.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import com.stucray.auth.user.User;
import com.stucray.auth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "OVERROUND_BFF_CLIENT_SECRET=test-secret",
    "OVERROUND_SIGNING_KEY_PATH=./target/test-signing-key.jwk"
})
@AutoConfigureMockMvc
class IssuerContractTest {

    private static final String REDIRECT_URI = "http://localhost:8091/login/oauth2/code/bff-client";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(new User(null, "testuser", passwordEncoder.encode("password"), true, LocalDateTime.now()));
    }

    @Test
    void openidConfigurationHasCorrectEndpoints() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://localhost:8090"))
            .andExpect(jsonPath("$.jwks_uri").value("http://localhost:8090/oauth2/jwks"))
            .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost:8090/oauth2/authorize"))
            .andExpect(jsonPath("$.token_endpoint").value("http://localhost:8090/oauth2/token"));
    }

    @Test
    void jwksEndpointServesRsaKey() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.keys").isArray())
            .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
            .andExpect(jsonPath("$.keys[0].kid").isNotEmpty());
    }

    @Test
    void authorizationCodeFlowProducesTokenVerifiableAgainstJwks() throws Exception {
        // 1. Log in to get an authenticated session
        MvcResult loginResult = mockMvc.perform(post("/login")
                .param("username", "testuser")
                .param("password", "password")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 2. Generate PKCE code verifier + challenge (S256)
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        // 3. Authorization request — should redirect straight to the redirect_uri (no consent).
        // Query params must be in the URI string (not .param()) so MockMvc sets getQueryString(),
        // which SAS 7's OAuth2EndpointUtils.getQueryParameters() requires to filter query vs form params.
        String authzUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", "bff-client")
            .queryParam("redirect_uri", REDIRECT_URI)
            .queryParam("scope", "openid profile")
            .queryParam("state", "test-state")
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();
        MvcResult authzResult = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = authzResult.getResponse().getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(location).build()
            .getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        // 4. Exchange code for tokens
        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("code_verifier", codeVerifier)
                .with(httpBasic("bff-client", "test-secret")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andReturn();

        // 5. Verify token signature against JWKS
        String tokenJson = tokenResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        SignedJWT signedJWT = SignedJWT.parse(accessToken);

        MvcResult jwksResult = mockMvc.perform(get("/oauth2/jwks")).andReturn();
        JWKSet jwkSet = JWKSet.parse(jwksResult.getResponse().getContentAsString());

        JWK matchingKey = jwkSet.getKeyByKeyId(signedJWT.getHeader().getKeyID());
        assertThat(matchingKey).as("JWKS must contain the key that signed the token").isNotNull();

        RSAPublicKey publicKey = (RSAPublicKey) matchingKey.toRSAKey().toPublicKey();
        assertThat(signedJWT.verify(new RSASSAVerifier(publicKey))).isTrue();
    }
}
