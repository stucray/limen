package com.stucray.limen.tenant;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface TenantRepository extends CrudRepository<Tenant, Long> {
    Optional<Tenant> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
