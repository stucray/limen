package com.stucray.limen.management.memberships;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface ClientMembershipRepository extends CrudRepository<ClientMembership, Long> {
    List<ClientMembership> findAllByClientMetadataId(Long clientMetadataId);
    Optional<ClientMembership> findByIdAndClientMetadataId(Long id, Long clientMetadataId);
    boolean existsByUserIdAndClientMetadataId(Long userId, Long clientMetadataId);
}
