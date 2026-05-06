package com.stucray.limen.memberships;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationMembershipRepository extends CrudRepository<ApplicationMembership, Long> {
    List<ApplicationMembership> findAllByApplicationId(Long applicationId);
    Optional<ApplicationMembership> findByIdAndApplicationId(Long id, Long applicationId);
    Optional<ApplicationMembership> findByUserIdAndApplicationId(Long userId, Long applicationId);
    boolean existsByUserIdAndApplicationId(Long userId, Long applicationId);
}
