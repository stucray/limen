package com.stucray.limen.ui.support;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Single source of truth for "give me a fresh tenant" across UI tests.
 *
 * <p>Each call seeds a uniquely-named tenant with one admin (owner) and one end user,
 * so concurrent tests cannot collide on slug/username and no teardown is needed —
 * the Testcontainers Postgres is discarded at JVM exit.
 *
 * <p>Goes through real service-layer code ({@link TenantProvisioningService} +
 * {@link UserRepository} + {@link PasswordEncoder}) rather than raw SQL, so test
 * preconditions exercise the same code paths production uses.
 */
@Component
public class TestTenantFactory {

    private static final String SHARED_TEST_PASSWORD = "secret123";

    private final TenantProvisioningService tenantProvisioningService;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TestTenantFactory(
        TenantProvisioningService tenantProvisioningService,
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.tenantProvisioningService = tenantProvisioningService;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public SeededTenant createTenant() {
        String suffix = uniqueSuffix();
        String slug = "t-" + suffix;
        String displayName = "Test Org " + suffix;
        String adminUsername = "admin-" + suffix;
        String endUserUsername = "user-" + suffix;

        Tenant tenant = tenantProvisioningService.createTenant(slug, displayName);
        String hash = passwordEncoder.encode(SHARED_TEST_PASSWORD);
        userRepository.save(new User(
            null, tenant.id(), adminUsername, hash, true, false, true, LocalDateTime.now()));
        userRepository.save(new User(
            null, tenant.id(), endUserUsername, hash, true, false, false, LocalDateTime.now()));

        return new SeededTenant(
            tenant.id(), slug, displayName,
            adminUsername, SHARED_TEST_PASSWORD,
            endUserUsername, SHARED_TEST_PASSWORD);
    }

    @Transactional
    public SeededSystemAdmin createSystemAdmin() {
        String suffix = uniqueSuffix();
        String username = "sysadmin-" + suffix;
        Tenant systemTenant = tenantRepository.findBySlug("system")
            .orElseThrow(() -> new IllegalStateException("system tenant not bootstrapped"));
        String hash = passwordEncoder.encode(SHARED_TEST_PASSWORD);
        userRepository.save(new User(
            null, systemTenant.id(), username, hash, true, false, false, LocalDateTime.now()));
        return new SeededSystemAdmin(username, SHARED_TEST_PASSWORD);
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public record SeededTenant(
        Long tenantId,
        String slug,
        String displayName,
        String adminUsername,
        String adminPassword,
        String endUserUsername,
        String endUserPassword
    ) {}

    public record SeededSystemAdmin(String username, String password) {}
}
