package com.stucray.limen.tenant;

import com.stucray.limen.auth.ott.EmailVerificationService;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Single transactional entry point for "new tenant + new owner user + send
 * verification email". Wraps the three steps so a failure in any of them
 * rolls back the whole bootstrap — no orphan {@code tenants} row when OTT
 * delivery throws, no orphan {@code users} row when seed-key generation does.
 *
 * <p>Two callers, one shape:
 *
 * <ul>
 *   <li>{@code SignupService} — public {@code /signup} form. Owner picks
 *       their own password; {@code mustChangePassword=false}.</li>
 *   <li>{@code SystemAdminController} — sysadmin {@code /manage/system/tenants/new}
 *       form. Owner is provisioned without a password they know; we generate
 *       a high-entropy placeholder, set {@code mustChangePassword=true}, and
 *       rely on the verification email + forced-change interceptor to walk
 *       the new owner through setting one.</li>
 * </ul>
 *
 * <p>Both paths produce the same downstream effects: {@code TenantCreatedEvent}
 * (emitted by {@link TenantProvisioningService#createTenant}), an unverified
 * owner user row, and a {@code VerificationOttIssuedEvent} (emitted by
 * {@link EmailVerificationService#issueVerification}).
 */
@Service
@Transactional
public class TenantUserBootstrap {

    private final TenantProvisioningService tenantProvisioningService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;

    public TenantUserBootstrap(
        TenantProvisioningService tenantProvisioningService,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        EmailVerificationService emailVerificationService
    ) {
        this.tenantProvisioningService = tenantProvisioningService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * The owner credential supplied by the caller. Sealed so the bootstrap
     * can pattern-match exhaustively on the two known modes; widening to a
     * third (e.g. SSO-only owners) is a deliberate shape change at the call
     * sites rather than a silent default.
     */
    public sealed interface OwnerCredentials {
        /** Owner provided their own password (signup form). */
        record Provided(String rawPassword) implements OwnerCredentials {}

        /** Server generates a placeholder; owner sets a real one after verifying email. */
        record GenerateRandom() implements OwnerCredentials {}
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public Tenant bootstrap(
        String slug, String displayName, String ownerEmail, OwnerCredentials credentials
    ) {
        Tenant tenant = tenantProvisioningService.createTenant(slug, displayName);

        String rawPassword = switch (credentials) {
            case OwnerCredentials.Provided p -> p.rawPassword();
            case OwnerCredentials.GenerateRandom g -> randomPlaceholderPassword();
        };
        boolean mustChangePassword = credentials instanceof OwnerCredentials.GenerateRandom;

        User owner = userRepository.save(new User(
            null, tenant.id(), ownerEmail,
            Objects.requireNonNull(passwordEncoder.encode(rawPassword)),
            true, mustChangePassword, true, false, LocalDateTime.now()
        ));

        emailVerificationService.issueVerification(tenant, owner);

        return tenant;
    }

    private static String randomPlaceholderPassword() {
        // Two concatenated UUIDs = ~256 bits of entropy. The owner never sees
        // this — the verification flow + mustChangePassword interceptor force
        // them through change-password before they can use the account.
        return UUID.randomUUID() + UUID.randomUUID().toString();
    }
}
