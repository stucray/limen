/**
 * The audit log: {@code AuditEvent} row, dispatch rules, registry, and writer.
 *
 * <p>Every audited domain event is persisted as one {@code audit_event} row by
 * {@code AuditEventWriter}, keyed by Tenant + Actor + occurred-at, with a JSON
 * payload that captures the event-specific fields. Other modules publish events
 * via Spring's {@code ApplicationEventPublisher}; this module owns the listener
 * side of that contract.
 *
 * <p>Cross-module API: the event types other modules publish live in the
 * {@code audit.events} sub-package and are exposed via the {@code @NamedInterface("events")}
 * marker. Internal sub-packages ({@code audit.dispatch}) are not callable from outside
 * this module.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.8 (Audit log) for behaviour, §4.15 (Package structure) for the cross-cutting view.
 */
package com.stucray.limen.audit;
