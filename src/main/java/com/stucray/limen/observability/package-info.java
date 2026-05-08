/**
 * Cross-cutting observability concerns: tenant tagging, named auth counters,
 * and the OpenTelemetry/Logback bridge.
 *
 * <p>Holds {@code TenantObservabilityFilter} (tags every request span and every
 * Micrometer meter created during the request with the resolved Tenant slug),
 * {@code AuditMetricsListener} (translates audit events into named counters such
 * as {@code limen.auth.signin.success} and {@code limen.auth.signin.failure}),
 * and {@code OtelLogbackInstaller} (programmatic Logback appender that ships log
 * records to the OTel collector with trace correlation).
 *
 * <p>Routine OTel + Micrometer wiring is configuration, not code; this module
 * exists for the bits that need application-aware logic.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.15 (Package structure) for the cross-cutting view, and
 * {@code docs/process/observability.md} for the LGTM stack runbook.
 */
package com.stucray.limen.observability;
