package com.stucray.limen.management.users;

import com.stucray.limen.audit.events.PasswordChangedEvent;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public UserManagementService(
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

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public void createUser(Long tenantId, String email, String temporaryPassword) {
        if (userRepository.existsByEmailAndTenantId(email, tenantId)) {
            throw new IllegalArgumentException("Email already exists in this tenant");
        }
        userRepository.save(new User(
            null, tenantId, email,
            Objects.requireNonNull(passwordEncoder.encode(temporaryPassword)),
            true, true, false, LocalDateTime.now()
        ));
    }

    public User getUser(Long userId, Long tenantId) {
        return userRepository.findById(userId)
            .filter(u -> u.tenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public void setEnabled(Long userId, Long tenantId, boolean enabled) {
        User user = getUser(userId, tenantId);
        userRepository.save(user.withEnabled(enabled));
    }

    public void deleteUser(Long userId, Long tenantId) {
        User user = getUser(userId, tenantId);
        userRepository.delete(user);
    }

    @Transactional
    public void resetPassword(Long userId, Long tenantId, String temporaryPassword) {
        User user = getUser(userId, tenantId);
        userRepository.save(user.withPasswordHash(
            Objects.requireNonNull(passwordEncoder.encode(temporaryPassword))));
        eventPublisher.publishEvent(
            new PasswordChangedEvent(tenantId, userId, PasswordChangedEvent.Trigger.ADMIN_RESET));
    }

    @Transactional
    public void changePassword(Long userId, Long tenantId, String newPassword) {
        User user = getUser(userId, tenantId);
        boolean wasForced = user.mustChangePassword();
        userRepository.save(user
            .withPasswordHash(Objects.requireNonNull(passwordEncoder.encode(newPassword)))
            .withMustChangePassword(false)
        );
        eventPublisher.publishEvent(new PasswordChangedEvent(
            tenantId, userId,
            wasForced ? PasswordChangedEvent.Trigger.FORCED : PasswordChangedEvent.Trigger.SELF_SERVICE));
    }

    public void setTenantOwner(Long userId, Long tenantId, boolean isTenantOwner) {
        User user = getUser(userId, tenantId);
        userRepository.save(user.withTenantOwner(isTenantOwner));
    }
}
