package com.stucray.limen.security.signing;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.Tenant;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of per-tenant signing-key rotation. Asserts the full
 * pipeline: storage swap → audit row → counter → JWKS endpoint serving both
 * keys for the duration of the grace window, the slice-2 prune flow that
 * reaps RETIRED keys whose grace window has elapsed, and the slice-3
 * scheduled batch path that picks eligible tenants by {@code created_at} age,
 * rotates each, and exercises ShedLock end-to-end.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Signing-key rotation flow: storage swap, audit row, counter, and JWKS endpoint advertising both keys")
class SigningKeyRotationFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired SigningKeyRotator rotator;
    @Autowired SigningKeyRotationSchedule schedule;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAudit() {
        jdbcTemplate.execute(
            "DELETE FROM audit_event WHERE event_type IN ('signing_key_rotated', 'signing_key_pruned')");
        jdbcTemplate.execute("DELETE FROM shedlock");
    }

    @Test
    @DisplayName("rotate(tenantId) retires the old key, advertises both kids on /oauth2/jwks, lands a signing_key_rotated audit row, and increments the counter")
    void rotateProducesAuditRowCounterAndJwksWithBothKeys() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "rot-" + suffix;
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Rotation " + suffix);

        String originalKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            String.class, tenant.id());

        double countBefore = counterCount();

        rotator.rotate(tenant.id());

        // Storage swap.
        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ?",
            Integer.class, tenant.id());
        assertThat(rowCount).isEqualTo(2);
        String newActiveKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            String.class, tenant.id());
        assertThat(newActiveKid).isNotEqualTo(originalKid);
        Object retiredAt = jdbcTemplate.queryForObject(
            "SELECT retired_at FROM tenant_signing_key WHERE tenant_id = ? AND status = 'RETIRED'",
            Object.class, tenant.id());
        assertThat(retiredAt).isNotNull();

        // Counter increment (synchronous on AFTER_COMMIT — no await needed).
        assertThat(counterCount() - countBefore).isEqualTo(1.0);

        // Audit row (async via Modulith publication registry — wait).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT target_id, details::text AS details FROM audit_event "
                    + "WHERE event_type = 'signing_key_rotated' AND tenant_id = ? "
                    + "ORDER BY occurred_at DESC LIMIT 1",
                tenant.id());
            assertThat(rows).isNotEmpty();
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("target_id")).isEqualTo(newActiveKid);
            String details = row.get("details").toString();
            assertThat(details).contains(originalKid);
            assertThat(details).contains(newActiveKid);
        });

        // JWKS endpoint advertises both kids; ACTIVE first.
        String jwksJson = mockMvc.perform(get("/t/" + slug + "/oauth2/jwks"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JWKSet jwks = JWKSet.parse(jwksJson);
        assertThat(jwks.getKeys()).extracting(JWK::getKeyID)
            .containsExactly(newActiveKid, originalKid);
    }

    @Test
    @DisplayName("pruneRetired(grace) deletes a RETIRED key whose retired_at exceeds the grace, lands a signing_key_pruned audit row, and increments the pruned counter")
    void prunePastGraceProducesAuditRowCounterAndDbDelete() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "prune-" + suffix;
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Prune " + suffix);

        rotator.rotate(tenant.id());
        String retiredKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'RETIRED'",
            String.class, tenant.id());
        jdbcTemplate.update(
            "UPDATE tenant_signing_key SET retired_at = CURRENT_TIMESTAMP - INTERVAL '48 hours' " +
                "WHERE tenant_id = ? AND status = 'RETIRED'",
            tenant.id());

        double pruneCountBefore = pruneCounterCount();

        rotator.pruneRetired(Duration.ofHours(24));

        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ?",
            Integer.class, tenant.id());
        assertThat(rowCount).isEqualTo(1);
        Integer retiredRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ? AND status = 'RETIRED'",
            Integer.class, tenant.id());
        assertThat(retiredRows).isZero();

        // Counter increment (synchronous on AFTER_COMMIT — no await needed).
        assertThat(pruneCounterCount() - pruneCountBefore).isEqualTo(1.0);

        // Audit row (async via Modulith publication registry — wait).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT target_id, details::text AS details FROM audit_event "
                    + "WHERE event_type = 'signing_key_pruned' AND tenant_id = ? "
                    + "ORDER BY occurred_at DESC LIMIT 1",
                tenant.id());
            assertThat(rows).isNotEmpty();
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("target_id")).isEqualTo(retiredKid);
            assertThat(row.get("details").toString()).contains(retiredKid);
        });
    }

    @Test
    @DisplayName("Scheduled batch (via SigningKeyRotationSchedule.run): backdated tenant is rotated, JWKS advertises both kids, counter increments, audit row lands, and ShedLock writes its lock row")
    void scheduledBatchRotatesEligibleTenantWritesShedLockRowAndProducesAuditTrail() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "sched-" + suffix;
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Sched " + suffix);

        String originalKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            String.class, tenant.id());
        // Backdate the ACTIVE key past the default 30-day rotation threshold.
        jdbcTemplate.update(
            "UPDATE tenant_signing_key SET created_at = CURRENT_TIMESTAMP - INTERVAL '40 days' " +
                "WHERE tenant_id = ? AND status = 'ACTIVE'",
            tenant.id());

        double countBefore = counterCount();

        // Going through the proxied schedule wrapper exercises the @SchedulerLock
        // advice, so a 'rotate-signing-keys' row lands in the shedlock table.
        schedule.run();

        // Storage swap: ACTIVE rotated, RETIRED row holds the original kid.
        String newActiveKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            String.class, tenant.id());
        assertThat(newActiveKid).isNotEqualTo(originalKid);
        Object retiredAt = jdbcTemplate.queryForObject(
            "SELECT retired_at FROM tenant_signing_key WHERE tenant_id = ? AND status = 'RETIRED'",
            Object.class, tenant.id());
        assertThat(retiredAt).isNotNull();

        // Counter incremented for the rotation.
        assertThat(counterCount() - countBefore).isEqualTo(1.0);

        // Audit row (async via Modulith publication registry — wait).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT target_id, details::text AS details FROM audit_event "
                    + "WHERE event_type = 'signing_key_rotated' AND tenant_id = ? "
                    + "ORDER BY occurred_at DESC LIMIT 1",
                tenant.id());
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0).get("target_id")).isEqualTo(newActiveKid);
            String details = rows.get(0).get("details").toString();
            assertThat(details).contains(originalKid).contains(newActiveKid);
        });

        // JWKS endpoint advertises both kids; ACTIVE first.
        String jwksJson = mockMvc.perform(get("/t/" + slug + "/oauth2/jwks"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JWKSet jwks = JWKSet.parse(jwksJson);
        assertThat(jwks.getKeys()).extracting(JWK::getKeyID)
            .containsExactly(newActiveKid, originalKid);

        // ShedLock recorded the lock for the named scheduler.
        Integer shedlockRowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shedlock WHERE name = 'rotate-signing-keys'",
            Integer.class);
        assertThat(shedlockRowCount).isEqualTo(1);
    }

    private double counterCount() {
        // Metric name is the public contract observed by Grafana/Mimir; assert
        // the literal so a rename in AuditMetricsListener trips this test.
        return meterRegistry.counter("limen.security.signing_key.rotated").count();
    }

    private double pruneCounterCount() {
        return meterRegistry.counter("limen.security.signing_key.pruned").count();
    }
}
