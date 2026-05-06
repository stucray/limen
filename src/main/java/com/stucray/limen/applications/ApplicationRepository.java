package com.stucray.limen.applications;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends CrudRepository<Application, Long> {
    List<Application> findAllByTenantId(Long tenantId);
    Optional<Application> findByIdAndTenantId(Long id, Long tenantId);
    boolean existsByNameAndTenantId(String name, Long tenantId);
}
