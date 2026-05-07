package com.stucray.limen.provisioning;

import com.stucray.limen.auth.ott.OttDispatcher;
import com.stucray.limen.auth.ott.OttIntent;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Single deep entry point for "provision a new tenant + owner user from a
 * form input." Both the public {@code /signup} flow and the system-admin
 * {@code /manage/system/tenants/new} flow funnel through {@link #provision}
 * — input normalisation, validation, atomic tenant + signing-key + owner +
 * verification-OTT writes, and the {@link TenantCreatedEvent} +
 * {@code VerificationOttIssuedEvent} emissions all happen in one transaction.
 *
 * <p>Callers describe their input via {@link NewTenantRequest}, built through
 * one of the named factories ({@link NewTenantRequest#fromSignupForm} /
 * {@link NewTenantRequest#fromSystemAdminForm}). Field-name mapping is
 * supplied through {@link FieldNames} so {@link Result.Rejected#field()} comes
 * back keyed to the form's input names — no caller needs to re-bind error
 * fields after the fact.
 *
 * <p>Owner credential modes are sealed under {@link OwnerCredentials}:
 * widening to a third (e.g. SSO-only) is a deliberate shape change at every
 * call site rather than a silent default.
 */
@Service
public class TenantProvisioner {

    private static final Pattern SLUG_FORMAT =
        Pattern.compile("^[a-z0-9][a-z0-9-]{1,46}[a-z0-9]$");
    private static final Pattern EMAIL_FORMAT =
        Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> RESERVED_SLUGS = Set.of(
        "system", "admin", "manage", "api", "www", "static", "health", "limen"
    );
    private static final int SLUG_MIN_LENGTH = 3;
    private static final int SLUG_MAX_LENGTH = 48;
    private static final int EMAIL_MAX_LENGTH = 255;

    private final TenantRepository tenantRepository;
    private final TenantProvisioningService tenantProvisioningService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OttDispatcher ottDispatcher;

    public TenantProvisioner(
        TenantRepository tenantRepository,
        TenantProvisioningService tenantProvisioningService,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        OttDispatcher ottDispatcher
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.ottDispatcher = ottDispatcher;
    }

    @Transactional
    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public Result provision(NewTenantRequest request) {
        String slug = trim(request.slug());
        String displayName = trim(request.displayName());
        String email = trim(request.ownerEmail());
        FieldNames fields = request.fieldNames();

        Result.@Nullable Rejected slugError = validateSlug(slug, fields.slug());
        if (slugError != null) return slugError;

        if (displayName.isBlank()) {
            return new Result.Rejected(fields.displayName(),
                fields.displayNameLabel() + " is required");
        }

        Result.@Nullable Rejected emailError = validateEmail(email, fields.email());
        if (emailError != null) return emailError;

        String rawPassword;
        boolean mustChangePassword;
        switch (request.ownerCredentials()) {
            case OwnerCredentials.Provided p -> {
                if (p.rawPassword().isBlank()) {
                    return new Result.Rejected(fields.password(), "Password is required");
                }
                rawPassword = p.rawPassword();
                mustChangePassword = false;
            }
            case OwnerCredentials.GenerateRandom g -> {
                rawPassword = randomPlaceholderPassword();
                mustChangePassword = true;
            }
        }

        Tenant tenant = tenantProvisioningService.createTenant(slug, displayName);
        User owner = userRepository.save(new User(
            null, tenant.id(), email,
            Objects.requireNonNull(passwordEncoder.encode(rawPassword)),
            true, mustChangePassword, true, false, LocalDateTime.now()
        ));
        ottDispatcher.issue(OttIntent.VERIFY_EMAIL, tenant, owner);

        return new Result.Provisioned(tenant, email);
    }

    private Result.@Nullable Rejected validateSlug(String slug, String fieldName) {
        if (slug.length() < SLUG_MIN_LENGTH || slug.length() > SLUG_MAX_LENGTH) {
            return new Result.Rejected(fieldName,
                "Slug must be between " + SLUG_MIN_LENGTH + " and " + SLUG_MAX_LENGTH + " characters");
        }
        if (!SLUG_FORMAT.matcher(slug).matches()) {
            return new Result.Rejected(fieldName,
                "Slug may only contain lowercase letters, digits, and hyphens, "
                    + "and must start and end with a letter or digit");
        }
        if (RESERVED_SLUGS.contains(slug)) {
            return new Result.Rejected(fieldName, "That slug is reserved and cannot be used");
        }
        if (tenantRepository.existsBySlug(slug)) {
            return new Result.Rejected(fieldName, "That slug is already taken");
        }
        return null;
    }

    private static Result.@Nullable Rejected validateEmail(String email, String fieldName) {
        if (email.isBlank()) {
            return new Result.Rejected(fieldName, "Email is required");
        }
        if (email.length() > EMAIL_MAX_LENGTH) {
            return new Result.Rejected(fieldName,
                "Email must be " + EMAIL_MAX_LENGTH + " characters or fewer");
        }
        if (!EMAIL_FORMAT.matcher(email).matches()) {
            return new Result.Rejected(fieldName, "Email must be a valid email address");
        }
        return null;
    }

    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static String randomPlaceholderPassword() {
        // Two concatenated UUIDs = ~256 bits of entropy. The owner never sees
        // this — the verification flow + mustChangePassword interceptor force
        // them through change-password before they can use the account.
        return UUID.randomUUID() + UUID.randomUUID().toString();
    }

    public sealed interface OwnerCredentials {
        /** Owner provided their own password (signup form). */
        record Provided(String rawPassword) implements OwnerCredentials {}

        /** Server generates a placeholder; owner sets a real one after verifying email. */
        record GenerateRandom() implements OwnerCredentials {}
    }

    public record NewTenantRequest(
        @Nullable String slug,
        @Nullable String displayName,
        @Nullable String ownerEmail,
        OwnerCredentials ownerCredentials,
        FieldNames fieldNames
    ) {
        public static NewTenantRequest fromSignupForm(
            @Nullable String slug,
            @Nullable String organizationName,
            @Nullable String email,
            @Nullable String password
        ) {
            return new NewTenantRequest(
                slug, organizationName, email,
                new OwnerCredentials.Provided(password == null ? "" : password),
                FieldNames.SIGNUP);
        }

        public static NewTenantRequest fromSystemAdminForm(
            @Nullable String slug,
            @Nullable String displayName,
            @Nullable String ownerEmail
        ) {
            return new NewTenantRequest(
                slug, displayName, ownerEmail,
                new OwnerCredentials.GenerateRandom(),
                FieldNames.SYSTEM_ADMIN);
        }
    }

    /**
     * Maps the four logical fields to the form's input names — and carries the
     * caller's preferred user-facing label for the displayName field, since the
     * signup form ("Organization name") and the system-admin form ("Display
     * name") word that field differently.
     *
     * <p>{@link Result.Rejected#field()} is keyed to the form's input name so
     * the caller never has to rebind error fields, and the displayName-required
     * message is composed against the caller's label so the user-visible UX
     * terminology of each form is preserved.
     */
    public record FieldNames(
        String slug,
        String displayName,
        String email,
        String password,
        String displayNameLabel
    ) {
        public static final FieldNames SIGNUP =
            new FieldNames("slug", "organizationName", "email", "password", "Organization name");
        public static final FieldNames SYSTEM_ADMIN =
            new FieldNames("slug", "displayName", "ownerEmail", "ownerEmail", "Display name");
    }

    public sealed interface Result {
        record Provisioned(Tenant tenant, String ownerEmail) implements Result {}
        record Rejected(String field, String message) implements Result {}
    }
}
