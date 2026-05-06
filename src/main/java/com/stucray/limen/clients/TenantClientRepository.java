package com.stucray.limen.clients;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface TenantClientRepository extends CrudRepository<TenantClient, Long> {
    List<TenantClient> findAllByApplicationIdAndTenantId(Long applicationId, Long tenantId);
    Optional<TenantClient> findByRegisteredClientId(String registeredClientId);
    Optional<TenantClient> findByRegisteredClientIdAndTenantId(String registeredClientId, Long tenantId);

    @Modifying
    @Query("DELETE FROM oauth2_registered_client WHERE id = :registeredClientId")
    void deleteRegisteredClient(String registeredClientId);
}
