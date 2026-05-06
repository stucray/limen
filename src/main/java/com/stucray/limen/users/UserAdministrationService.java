package com.stucray.limen.users;

import com.stucray.limen.audit.events.AccountUnlockedEvent;
import com.stucray.limen.audit.events.PasswordChangedEvent;
import com.stucray.limen.audit.events.TenantOwnershipGrantedEvent;
import com.stucray.limen.audit.events.TenantOwnershipRevokedEvent;
import com.stucray.limen.audit.events.UserCreatedEvent;
import com.stucray.limen.audit.events.UserDeletedEvent;
import com.stucray.limen.audit.events.UserDisabledEvent;
import com.stucray.limen.audit.events.UserEnabledEvent;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Single deep service owning every admin-side mutation of user accounts within
 * a tenant. Each method is intent-named (rather than a {@code set(boolean)}
 * shape), encodes its pre-conditions inline, and publishes one audit event on
 * success.
 *
 * <p>Invariants enforced on every state-changing call:
 * <ul>
 *   <li>{@code actor != target} on disable / delete / revokeTenantOwnership —
 *       an admin cannot lock themselves out of the actions needed to undo it.</li>
 *   <li>The action must not orphan the tenant: at least one enabled tenant-owner
 *       must remain after disable / delete / revokeTenantOwnership.</li>
 *   <li>Granted users must already be enabled and email-verified.</li>
 * </ul>
 *
 * <p>Self-service password change does NOT live here — it lives inline on
 * {@link com.stucray.limen.auth.login.TenantPasswordChangeFlow}, the only
 * caller of that path. The {@code Trigger} discriminator on
 * {@link PasswordChangedEvent} is the historical seam.
 */
@Service
public class UserAdministrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public UserAdministrationService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    public List<User> listUsers(Long tenantId) {
        return userRepository.findAllByTenantId(tenantId);
    }

    public User getUser(Long userId, Long tenantId) {
        return userRepository.findById(userId)
            .filter(u -> u.tenantId().equals(tenantId))
            .orElseThrow(() -> new UserAdminException.UserNotInTenant("lookup"));
    }

    @Transactional
    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public void createUser(Long tenantId, Long actorUserId, String email, String temporaryPassword) {
        if (userRepository.existsByEmailAndTenantId(email, tenantId)) {
            throw new IllegalArgumentException("Email already exists in this tenant");
        }
        User saved = userRepository.save(new User(
            null, tenantId, email,
            Objects.requireNonNull(passwordEncoder.encode(temporaryPassword)),
            true, true, false, true, LocalDateTime.now()
        ));
        eventPublisher.publishEvent(new UserCreatedEvent(tenantId, actorUserId, saved.id(), email));
    }

    @Transactional
    public void enable(Long userId, Long tenantId, Long actorUserId) {
        User user = getUser(userId, tenantId);
        if (user.enabled()) return; // idempotent
        userRepository.save(user.withEnabled(true));
        eventPublisher.publishEvent(new UserEnabledEvent(tenantId, actorUserId, userId, user.email()));
    }

    @Transactional
    public void disable(Long userId, Long tenantId, Long actorUserId) {
        User user = getUser(userId, tenantId);
        if (Objects.equals(actorUserId, userId)) {
            throw new UserAdminException.CannotTargetSelf("disable",
                "You cannot disable your own account");
        }
        if (!user.enabled()) return; // idempotent
        if (user.tenantOwner() && wouldOrphanTenant(tenantId)) {
            throw new UserAdminException.WouldOrphanTenant("disable",
                "Cannot disable the last enabled tenant owner");
        }
        userRepository.save(user.withEnabled(false));
        eventPublisher.publishEvent(new UserDisabledEvent(tenantId, actorUserId, userId, user.email()));
    }

    @Transactional
    public void deleteUser(Long userId, Long tenantId, Long actorUserId) {
        User user = getUser(userId, tenantId);
        if (Objects.equals(actorUserId, userId)) {
            throw new UserAdminException.CannotTargetSelf("delete",
                "You cannot delete your own account");
        }
        if (user.tenantOwner() && user.enabled() && wouldOrphanTenant(tenantId)) {
            throw new UserAdminException.WouldOrphanTenant("delete",
                "Cannot delete the last enabled tenant owner");
        }
        userRepository.delete(user);
        eventPublisher.publishEvent(new UserDeletedEvent(tenantId, actorUserId, userId, user.email()));
    }

    @Transactional
    public void grantTenantOwnership(Long userId, Long tenantId, Long actorUserId) {
        User user = getUser(userId, tenantId);
        if (!user.enabled()) {
            throw new UserAdminException.TargetNotEligible("grantOwner",
                "Cannot grant ownership to a disabled user");
        }
        if (!user.emailVerified()) {
            throw new UserAdminException.TargetNotEligible("grantOwner",
                "Cannot grant ownership to a user with an unverified email");
        }
        if (user.tenantOwner()) return; // idempotent
        userRepository.save(user.withTenantOwner(true));
        eventPublisher.publishEvent(new TenantOwnershipGrantedEvent(tenantId, actorUserId, userId, user.email()));
    }

    @Transactional
    public void revokeTenantOwnership(Long userId, Long tenantId, Long actorUserId) {
        User user = getUser(userId, tenantId);
        if (Objects.equals(actorUserId, userId)) {
            throw new UserAdminException.CannotTargetSelf("revokeOwner",
                "You cannot revoke your own ownership");
        }
        if (!user.tenantOwner()) return; // idempotent
        if (user.enabled() && wouldOrphanTenant(tenantId)) {
            throw new UserAdminException.WouldOrphanTenant("revokeOwner",
                "Cannot revoke ownership from the last enabled tenant owner");
        }
        userRepository.save(user.withTenantOwner(false));
        eventPublisher.publishEvent(new TenantOwnershipRevokedEvent(tenantId, actorUserId, userId, user.email()));
    }

    /**
     * Rotate the password hash AND clear lockout state in the same transaction.
     * Without the lockout-clear, an admin could reset a locked user's password
     * and the user would still be rejected by the {@code TenantAuthProvider}
     * pre-check on next login — never reaching the forced-change form. Fires
     * {@link PasswordChangedEvent} always, plus {@link AccountUnlockedEvent}
     * iff the user was carrying lockout state at reset time.
     */
    @Transactional
    public void resetPassword(Long userId, Long tenantId, Long actorUserId, String temporaryPassword) {
        User user = getUser(userId, tenantId);
        boolean wasLocked = user.lockedUntil() != null;
        userRepository.save(user
            .withPasswordHash(Objects.requireNonNull(passwordEncoder.encode(temporaryPassword)))
            .withFailedLoginAttempts(0)
            .withLockedUntil(null));
        eventPublisher.publishEvent(new PasswordChangedEvent(
            tenantId, userId, PasswordChangedEvent.Trigger.ADMIN_RESET));
        if (wasLocked) {
            eventPublisher.publishEvent(new AccountUnlockedEvent(
                tenantId, actorUserId, userId, user.email()));
        }
    }

    @Transactional
    public void unlockAccount(Long userId, Long tenantId, Long actorUserId) {
        User user = getUser(userId, tenantId);
        if (user.lockedUntil() == null && user.failedLoginAttempts() == 0) return; // idempotent
        userRepository.save(user.withFailedLoginAttempts(0).withLockedUntil(null));
        eventPublisher.publishEvent(new AccountUnlockedEvent(
            tenantId, actorUserId, userId, user.email()));
    }

    /**
     * Pre-check helper: would the action that follows reduce the count of
     * enabled tenant-owners to zero? Caller has already verified the target
     * is currently an enabled tenant-owner; this returns true iff there is
     * only one such owner right now (i.e., this one).
     */
    private boolean wouldOrphanTenant(Long tenantId) {
        return userRepository.countByTenantIdAndTenantOwnerTrueAndEnabledTrue(tenantId) <= 1;
    }
}
