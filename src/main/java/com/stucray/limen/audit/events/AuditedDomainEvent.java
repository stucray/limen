package com.stucray.limen.audit.events;

/**
 * Marker for transactionally-emitted domain events that an audit listener
 * should record. Every concrete implementor is a {@code record} living in
 * this package, so the set is enumerable here and via {@link AuditRegistry}.
 *
 * <p>Two reasons this exists:
 * <ul>
 *   <li><b>Type-narrows the AFTER_COMMIT audit listener.</b>
 *       {@code AuditDispatcher.onAfterCommit} is annotated
 *       {@code @ApplicationModuleListener}, which delegates through
 *       Modulith's {@code PersistentApplicationEventMulticaster}. The
 *       multicaster persists every published event whose declared listener
 *       parameter type is assignable from the event's runtime type. A bare
 *       {@code Object} parameter would catch every framework startup event
 *       ({@code ContextRefreshedEvent}, {@code ApplicationReadyEvent}, ...)
 *       and try to JSON-serialize the source — which is the entire
 *       application context — into {@code event_publication}. This marker
 *       confines persistence to events we actually care about.</li>
 *   <li><b>Documents intent.</b> If a future contributor adds a domain event
 *       and wants it audited, the type system points at the marker.</li>
 * </ul>
 *
 * <p>{@code RateLimitHitEvent} and the Spring Security auth events
 * deliberately do <em>not</em> implement this marker — they fire outside any
 * transaction and run through the IMMEDIATE {@code @EventListener} branch,
 * which never touches the publication registry.
 */
public interface AuditedDomainEvent {
}
