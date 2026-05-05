package com.stucray.limen.user;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends CrudRepository<User, Long> {
    Optional<User> findByEmailAndTenantId(String email, Long tenantId);
    Optional<User> findByIdAndTenantId(Long id, Long tenantId);
    List<User> findAllByTenantId(Long tenantId);
    boolean existsByEmailAndTenantId(String email, Long tenantId);
    long countByTenantIdAndTenantOwnerTrueAndEnabledTrue(Long tenantId);
}
