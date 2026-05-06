package com.stucray.limen.roles;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends CrudRepository<Role, Long> {
    List<Role> findAllByApplicationId(Long applicationId);
    Optional<Role> findByIdAndApplicationId(Long id, Long applicationId);
    boolean existsByNameAndApplicationId(String name, Long applicationId);
}
