package com.stucray.limen.management.applications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final JdbcTemplate jdbcTemplate;

    public ApplicationService(ApplicationRepository applicationRepository, JdbcTemplate jdbcTemplate) {
        this.applicationRepository = applicationRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Application> listApplications(Long tenantId) {
        return applicationRepository.findAllByTenantId(tenantId);
    }

    public Application getApplication(Long appId, Long tenantId) {
        return applicationRepository.findByIdAndTenantId(appId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Application not found"));
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public Application createApplication(Long tenantId, String name, String description) {
        if (applicationRepository.existsByNameAndTenantId(name, tenantId)) {
            throw new IllegalArgumentException("An application named '" + name + "' already exists in this tenant");
        }
        return applicationRepository.save(
            new Application(null, tenantId, name, description, LocalDateTime.now())
        );
    }

    public void updateApplication(Long appId, Long tenantId, String name, String description) {
        Application app = getApplication(appId, tenantId);
        applicationRepository.save(app.withName(name).withDescription(description));
    }

    public void deleteApplication(Long appId, Long tenantId) {
        Application app = getApplication(appId, tenantId);
        Integer clientCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM oauth2_registered_client WHERE application_id = ?",
            Integer.class, appId
        );
        if (clientCount != null && clientCount > 0) {
            throw new IllegalStateException("Cannot delete application with attached Clients");
        }
        applicationRepository.delete(app);
    }
}
