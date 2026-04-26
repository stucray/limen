package com.stucray.limen.management.users;

import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

    public void createUser(Long tenantId, String username, String temporaryPassword) {
        if (userRepository.existsByUsernameAndTenantId(username, tenantId)) {
            throw new IllegalArgumentException("Username already exists in this tenant");
        }
        userRepository.save(new User(
            null, tenantId, username,
            passwordEncoder.encode(temporaryPassword),
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
        userRepository.save(user.withPasswordHash(passwordEncoder.encode(temporaryPassword)));
    }

    public void changePassword(Long userId, Long tenantId, String newPassword) {
        User user = getUser(userId, tenantId);
        userRepository.save(user
            .withPasswordHash(passwordEncoder.encode(newPassword))
            .withMustChangePassword(false)
        );
    }

    public void setTenantOwner(Long userId, Long tenantId, boolean isTenantOwner) {
        User user = getUser(userId, tenantId);
        userRepository.save(user.withTenantOwner(isTenantOwner));
    }
}
