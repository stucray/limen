package com.stucray.limen.management.users;

import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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

    public void resetPassword(Long userId, Long tenantId, String temporaryPassword) {
        User user = getUser(userId, tenantId);
        userRepository.save(user.withPasswordHash(
            Objects.requireNonNull(passwordEncoder.encode(temporaryPassword))));
    }

    public void changePassword(Long userId, Long tenantId, String newPassword) {
        User user = getUser(userId, tenantId);
        userRepository.save(user
            .withPasswordHash(Objects.requireNonNull(passwordEncoder.encode(newPassword)))
            .withMustChangePassword(false)
        );
    }

    public void setTenantOwner(Long userId, Long tenantId, boolean isTenantOwner) {
        User user = getUser(userId, tenantId);
        userRepository.save(user.withTenantOwner(isTenantOwner));
    }
}
