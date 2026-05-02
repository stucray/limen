---
title: "How-to: Implement Multitenancy :: Spring Authorization Server"
source: "https://docs.spring.io/spring-authorization-server/reference/guides/how-to-multitenancy.html"
author:
published:
created: 2026-04-26
description:
tags:
  - "clippings"
---
## How-to: Implement Multitenancy

This guide shows how to customize Spring Authorization Server to support multiple issuers per host in a multi-tenant hosting configuration. The purpose of this guide is to demonstrate a general pattern for building multi-tenant capable components for Spring Authorization Server, which can also be applied to other components to suit your needs.

## Define the tenant identifier

The [OpenID Connect 1.0 Provider Configuration Endpoint](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html#oidc-provider-configuration-endpoint) and [OAuth2 Authorization Server Metadata Endpoint](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html#oauth2-authorization-server-metadata-endpoint) allow for path components in the issuer identifier value, which effectively enables supporting multiple issuers per host.

For example, an [OpenID Provider Configuration Request](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest) "http://localhost:9000/issuer1/.well-known/openid-configuration" or an [Authorization Server Metadata Request](https://datatracker.ietf.org/doc/html/rfc8414#section-3.1) "http://localhost:9000/.well-known/oauth-authorization-server/issuer1" would return the following configuration metadata:

```json
{
  "issuer": "http://localhost:9000/issuer1",
  "authorization_endpoint": "http://localhost:9000/issuer1/oauth2/authorize",
  "token_endpoint": "http://localhost:9000/issuer1/oauth2/token",
  "jwks_uri": "http://localhost:9000/issuer1/oauth2/jwks",
  "revocation_endpoint": "http://localhost:9000/issuer1/oauth2/revoke",
  "introspection_endpoint": "http://localhost:9000/issuer1/oauth2/introspect",
  ...
}
```

|  | The base URL of the [Protocol Endpoints](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html) is the issuer identifier value. |
| --- | --- |

Essentially, an issuer identifier with a path component represents the *"tenant identifier"*.

## Enable multiple issuers

Support for using multiple issuers per host is disabled by default. To enable, add the following configuration:

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

@Configuration(proxyBeanMethods = false)
public class AuthorizationServerSettingsConfig {

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .multipleIssuersAllowed(true)    (1)
                .build();
    }

}
```

| **1** | Set to `true` to allow usage of multiple issuers per host. |
| --- | --- |

## Create a component registry

We start by building a simple registry for managing the concrete components for each tenant. The registry contains the logic for retrieving a concrete implementation of a particular class using the issuer identifier value.

We will use the following class in each of the delegating implementations below:

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class TenantPerIssuerComponentRegistry {
    private final ConcurrentMap<String, Map<Class<?>, Object>> registry = new ConcurrentHashMap<>();

    public <T> void register(String tenantId, Class<T> componentClass, T component) {    (1)
        Assert.hasText(tenantId, "tenantId cannot be empty");
        Assert.notNull(componentClass, "componentClass cannot be null");
        Assert.notNull(component, "component cannot be null");
        Map<Class<?>, Object> components = this.registry.computeIfAbsent(tenantId, (key) -> new ConcurrentHashMap<>());
        components.put(componentClass, component);
    }

    @Nullable
    public <T> T get(Class<T> componentClass) {
        AuthorizationServerContext context = AuthorizationServerContextHolder.getContext();
        if (context == null || context.getIssuer() == null) {
            return null;
        }
        for (Map.Entry<String, Map<Class<?>, Object>> entry : this.registry.entrySet()) {
            if (context.getIssuer().endsWith(entry.getKey())) {
                return componentClass.cast(entry.getValue().get(componentClass));
            }
        }
        return null;
    }
}
```

| **1** | Component registration implicitly enables an allowlist of approved issuers that can be used. |
| --- | --- |

|  | This registry is designed to allow components to be easily registered at startup to support adding tenants statically, but also supports [adding tenants dynamically](#multi-tenant-add-tenants-dynamically) at runtime. |
| --- | --- |

## Create multi-tenant components

The components that require multi-tenant capability are:

For each of these components, an implementation of a composite can be provided that delegates to the concrete component associated to the *"requested"* issuer identifier.

Let’s step through a scenario of how to customize Spring Authorization Server to support 2x tenants for each multi-tenant capable component.

### Multi-tenant RegisteredClientRepository

The following example shows a sample implementation of a [`RegisteredClientRepository`](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html#registered-client-repository) that is composed of 2x `JdbcRegisteredClientRepository` instances, where each instance is mapped to an issuer identifier:

```java
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class RegisteredClientRepositoryConfig {

    @Bean
    public RegisteredClientRepository registeredClientRepository(
            @Qualifier("issuer1-data-source") DataSource issuer1DataSource,
            @Qualifier("issuer2-data-source") DataSource issuer2DataSource,
            TenantPerIssuerComponentRegistry componentRegistry) {

        JdbcRegisteredClientRepository issuer1RegisteredClientRepository =
                new JdbcRegisteredClientRepository(new JdbcTemplate(issuer1DataSource));    (1)

        issuer1RegisteredClientRepository.save(
                RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId("client-1")
                        .clientSecret("{noop}secret")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .scope("scope-1")
                        .build()
        );

        JdbcRegisteredClientRepository issuer2RegisteredClientRepository =
                new JdbcRegisteredClientRepository(new JdbcTemplate(issuer2DataSource));    (2)

        issuer2RegisteredClientRepository.save(
                RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId("client-2")
                        .clientSecret("{noop}secret")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .scope("scope-2")
                        .build()
        );

        componentRegistry.register("issuer1", RegisteredClientRepository.class, issuer1RegisteredClientRepository);
        componentRegistry.register("issuer2", RegisteredClientRepository.class, issuer2RegisteredClientRepository);

        return new DelegatingRegisteredClientRepository(componentRegistry);
    }

    private static class DelegatingRegisteredClientRepository implements RegisteredClientRepository {    (3)

        private final TenantPerIssuerComponentRegistry componentRegistry;

        private DelegatingRegisteredClientRepository(TenantPerIssuerComponentRegistry componentRegistry) {
            this.componentRegistry = componentRegistry;
        }

        @Override
        public void save(RegisteredClient registeredClient) {
            getRegisteredClientRepository().save(registeredClient);
        }

        @Override
        public RegisteredClient findById(String id) {
            return getRegisteredClientRepository().findById(id);
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return getRegisteredClientRepository().findByClientId(clientId);
        }

        private RegisteredClientRepository getRegisteredClientRepository() {
            RegisteredClientRepository registeredClientRepository =
                    this.componentRegistry.get(RegisteredClientRepository.class);    (4)
            Assert.state(registeredClientRepository != null,
                    "RegisteredClientRepository not found for \"requested\" issuer identifier.");    (5)
            return registeredClientRepository;
        }

    }

}
```

|  | Click on the "Expand folded text" icon in the code sample above to display the full example. |
| --- | --- |

| **1** | A `JdbcRegisteredClientRepository` instance mapped to issuer identifier `issuer1` and using a dedicated `DataSource`. |
| --- | --- |
| **2** | A `JdbcRegisteredClientRepository` instance mapped to issuer identifier `issuer2` and using a dedicated `DataSource`. |
| **3** | A composite implementation of a `RegisteredClientRepository` that delegates to a `JdbcRegisteredClientRepository` mapped to the *"requested"* issuer identifier. |
| **4** | Obtain the `JdbcRegisteredClientRepository` that is mapped to the *"requested"* issuer identifier indicated by `AuthorizationServerContext.getIssuer()`. |
| **5** | If unable to find `JdbcRegisteredClientRepository`, then error since the *"requested"* issuer identifier is not in the allowlist of approved issuers. |

|  | Explicitly configuring the issuer identifier via `AuthorizationServerSettings.builder().issuer("http://localhost:9000")` forces to a single-tenant configuration. Avoid explicitly configuring the issuer identifier when using a multi-tenant hosting configuration. |
| --- | --- |

In the preceding example, each of the `JdbcRegisteredClientRepository` instances are configured with a `JdbcTemplate` and associated `DataSource`. This is important in a multi-tenant configuration as a primary requirement is to have the ability to isolate the data from each tenant.

Configuring a dedicated `DataSource` for each component instance provides the flexibility to isolate the data in its own schema within the same database instance or alternatively isolate the data in a separate database instance altogether.

The following example shows a sample configuration of 2x `DataSource` `@Bean` (one for each tenant) that are used by the multi-tenant capable components:

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean("issuer1-data-source")
    public EmbeddedDatabase issuer1DataSource() {
        return new EmbeddedDatabaseBuilder()
                .setName("issuer1-db")    (1)
                .setType(EmbeddedDatabaseType.H2)
                .setScriptEncoding("UTF-8")
                .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")
                .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql")
                .addScript("org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql")
                .build();
    }

    @Bean("issuer2-data-source")
    public EmbeddedDatabase issuer2DataSource() {
        return new EmbeddedDatabaseBuilder()
                .setName("issuer2-db")    (2)
                .setType(EmbeddedDatabaseType.H2)
                .setScriptEncoding("UTF-8")
                .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")
                .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql")
                .addScript("org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql")
                .build();
    }

}
```

| **1** | Use a separate H2 database instance using `issuer1-db` as the name. |
| --- | --- |
| **2** | Use a separate H2 database instance using `issuer2-db` as the name. |

### Multi-tenant OAuth2AuthorizationService

The following example shows a sample implementation of an [`OAuth2AuthorizationService`](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html#oauth2-authorization-service) that is composed of 2x `JdbcOAuth2AuthorizationService` instances, where each instance is mapped to an issuer identifier:

```java
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class OAuth2AuthorizationServiceConfig {

    @Bean
    public OAuth2AuthorizationService authorizationService(
            @Qualifier("issuer1-data-source") DataSource issuer1DataSource,
            @Qualifier("issuer2-data-source") DataSource issuer2DataSource,
            TenantPerIssuerComponentRegistry componentRegistry,
            RegisteredClientRepository registeredClientRepository) {

        componentRegistry.register("issuer1", OAuth2AuthorizationService.class,
                new JdbcOAuth2AuthorizationService(    (1)
                        new JdbcTemplate(issuer1DataSource), registeredClientRepository));
        componentRegistry.register("issuer2", OAuth2AuthorizationService.class,
                new JdbcOAuth2AuthorizationService(    (2)
                        new JdbcTemplate(issuer2DataSource), registeredClientRepository));

        return new DelegatingOAuth2AuthorizationService(componentRegistry);
    }

    private static class DelegatingOAuth2AuthorizationService implements OAuth2AuthorizationService {    (3)

        private final TenantPerIssuerComponentRegistry componentRegistry;

        private DelegatingOAuth2AuthorizationService(TenantPerIssuerComponentRegistry componentRegistry) {
            this.componentRegistry = componentRegistry;
        }

        @Override
        public void save(OAuth2Authorization authorization) {
            getAuthorizationService().save(authorization);
        }

        @Override
        public void remove(OAuth2Authorization authorization) {
            getAuthorizationService().remove(authorization);
        }

        @Override
        public OAuth2Authorization findById(String id) {
            return getAuthorizationService().findById(id);
        }

        @Override
        public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
            return getAuthorizationService().findByToken(token, tokenType);
        }

        private OAuth2AuthorizationService getAuthorizationService() {
            OAuth2AuthorizationService authorizationService =
                    this.componentRegistry.get(OAuth2AuthorizationService.class);    (4)
            Assert.state(authorizationService != null,
                    "OAuth2AuthorizationService not found for \"requested\" issuer identifier.");    (5)
            return authorizationService;
        }

    }

}
```

| **1** | A `JdbcOAuth2AuthorizationService` instance mapped to issuer identifier `issuer1` and using a dedicated `DataSource`. |
| --- | --- |
| **2** | A `JdbcOAuth2AuthorizationService` instance mapped to issuer identifier `issuer2` and using a dedicated `DataSource`. |
| **3** | A composite implementation of an `OAuth2AuthorizationService` that delegates to a `JdbcOAuth2AuthorizationService` mapped to the *"requested"* issuer identifier. |
| **4** | Obtain the `JdbcOAuth2AuthorizationService` that is mapped to the *"requested"* issuer identifier indicated by `AuthorizationServerContext.getIssuer()`. |
| **5** | If unable to find `JdbcOAuth2AuthorizationService`, then error since the *"requested"* issuer identifier is not in the allowlist of approved issuers. |

### Multi-tenant OAuth2AuthorizationConsentService

The following example shows a sample implementation of an [`OAuth2AuthorizationConsentService`](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html#oauth2-authorization-consent-service) that is composed of 2x `JdbcOAuth2AuthorizationConsentService` instances, where each instance is mapped to an issuer identifier:

```java
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class OAuth2AuthorizationConsentServiceConfig {

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            @Qualifier("issuer1-data-source") DataSource issuer1DataSource,
            @Qualifier("issuer2-data-source") DataSource issuer2DataSource,
            TenantPerIssuerComponentRegistry componentRegistry,
            RegisteredClientRepository registeredClientRepository) {

        componentRegistry.register("issuer1", OAuth2AuthorizationConsentService.class,
                new JdbcOAuth2AuthorizationConsentService(    (1)
                        new JdbcTemplate(issuer1DataSource), registeredClientRepository));
        componentRegistry.register("issuer2", OAuth2AuthorizationConsentService.class,
                new JdbcOAuth2AuthorizationConsentService(    (2)
                        new JdbcTemplate(issuer2DataSource), registeredClientRepository));

        return new DelegatingOAuth2AuthorizationConsentService(componentRegistry);
    }

    private static class DelegatingOAuth2AuthorizationConsentService implements OAuth2AuthorizationConsentService {    (3)

        private final TenantPerIssuerComponentRegistry componentRegistry;

        private DelegatingOAuth2AuthorizationConsentService(TenantPerIssuerComponentRegistry componentRegistry) {
            this.componentRegistry = componentRegistry;
        }

        @Override
        public void save(OAuth2AuthorizationConsent authorizationConsent) {
            getAuthorizationConsentService().save(authorizationConsent);
        }

        @Override
        public void remove(OAuth2AuthorizationConsent authorizationConsent) {
            getAuthorizationConsentService().remove(authorizationConsent);
        }

        @Override
        public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
            return getAuthorizationConsentService().findById(registeredClientId, principalName);
        }

        private OAuth2AuthorizationConsentService getAuthorizationConsentService() {
            OAuth2AuthorizationConsentService authorizationConsentService =
                    this.componentRegistry.get(OAuth2AuthorizationConsentService.class);    (4)
            Assert.state(authorizationConsentService != null,
                    "OAuth2AuthorizationConsentService not found for \"requested\" issuer identifier.");    (5)
            return authorizationConsentService;
        }

    }

}
```

| **1** | A `JdbcOAuth2AuthorizationConsentService` instance mapped to issuer identifier `issuer1` and using a dedicated `DataSource`. |
| --- | --- |
| **2** | A `JdbcOAuth2AuthorizationConsentService` instance mapped to issuer identifier `issuer2` and using a dedicated `DataSource`. |
| **3** | A composite implementation of an `OAuth2AuthorizationConsentService` that delegates to a `JdbcOAuth2AuthorizationConsentService` mapped to the *"requested"* issuer identifier. |
| **4** | Obtain the `JdbcOAuth2AuthorizationConsentService` that is mapped to the *"requested"* issuer identifier indicated by `AuthorizationServerContext.getIssuer()`. |
| **5** | If unable to find `JdbcOAuth2AuthorizationConsentService`, then error since the *"requested"* issuer identifier is not in the allowlist of approved issuers. |

### Multi-tenant JWKSource

And finally, the following example shows a sample implementation of a `JWKSource<SecurityContext>` that is composed of 2x `JWKSet` instances, where each instance is mapped to an issuer identifier:

```java
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class JWKSourceConfig {

    @Bean
    public JWKSource<SecurityContext> jwkSource(TenantPerIssuerComponentRegistry componentRegistry) {
        componentRegistry.register("issuer1", JWKSet.class, new JWKSet(generateRSAJwk()));    (1)
        componentRegistry.register("issuer2", JWKSet.class, new JWKSet(generateRSAJwk()));    (2)

        return new DelegatingJWKSource(componentRegistry);
    }

    private static RSAKey generateRSAJwk() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private static class DelegatingJWKSource implements JWKSource<SecurityContext> {    (3)

        private final TenantPerIssuerComponentRegistry componentRegistry;

        private DelegatingJWKSource(TenantPerIssuerComponentRegistry componentRegistry) {
            this.componentRegistry = componentRegistry;
        }

        @Override
        public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) throws KeySourceException {
            return jwkSelector.select(getJwkSet());
        }

        private JWKSet getJwkSet() {
            JWKSet jwkSet = this.componentRegistry.get(JWKSet.class);    (4)
            Assert.state(jwkSet != null, "JWKSet not found for \"requested\" issuer identifier.");    (5)
            return jwkSet;
        }

    }

}
```

| **1** | A `JWKSet` instance mapped to issuer identifier `issuer1`. |
| --- | --- |
| **2** | A `JWKSet` instance mapped to issuer identifier `issuer2`. |
| **3** | A composite implementation of an `JWKSource<SecurityContext>` that uses the `JWKSet` mapped to the *"requested"* issuer identifier. |
| **4** | Obtain the `JWKSet` that is mapped to the *"requested"* issuer identifier indicated by `AuthorizationServerContext.getIssuer()`. |
| **5** | If unable to find `JWKSet`, then error since the *"requested"* issuer identifier is not in the allowlist of approved issuers. |

## Add Tenants Dynamically

If the number of tenants is dynamic and can change at runtime, defining each `DataSource` as a `@Bean` may not be feasible. In this case, the `DataSource` and corresponding components can be registered through other means at application startup and/or runtime.

The following example shows a Spring `@Service` capable of adding tenants dynamically:

```java
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

@Service
public class TenantService {

    private final TenantPerIssuerComponentRegistry componentRegistry;

    public TenantService(TenantPerIssuerComponentRegistry componentRegistry) {
        this.componentRegistry = componentRegistry;
    }

    public void createTenant(String tenantId) {
        EmbeddedDatabase dataSource = createDataSource(tenantId);
        JdbcTemplate jdbcOperations = new JdbcTemplate(dataSource);

        RegisteredClientRepository registeredClientRepository =
                new JdbcRegisteredClientRepository(jdbcOperations);
        this.componentRegistry.register(tenantId, RegisteredClientRepository.class, registeredClientRepository);

        OAuth2AuthorizationService authorizationService =
                new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
        this.componentRegistry.register(tenantId, OAuth2AuthorizationService.class, authorizationService);

        OAuth2AuthorizationConsentService authorizationConsentService =
                new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
        this.componentRegistry.register(tenantId, OAuth2AuthorizationConsentService.class, authorizationConsentService);

        JWKSet jwkSet = new JWKSet(generateRSAJwk());
        this.componentRegistry.register(tenantId, JWKSet.class, jwkSet);
    }

    private EmbeddedDatabase createDataSource(String tenantId) {
        return new EmbeddedDatabaseBuilder()
                .setName(tenantId)
                .setType(EmbeddedDatabaseType.H2)
                .setScriptEncoding("UTF-8")
                .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")
                .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql")
                .addScript("org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql")
                .build();
    }

    private static RSAKey generateRSAJwk() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

}
```