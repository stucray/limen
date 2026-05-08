/**
 * Cross-module API for one-time-token (OTT) issuance and completion.
 *
 * <p>Other modules call into this surface through two services and one
 * extension point:
 * <ul>
 *   <li>{@link OttDispatcher} — the single entry point for issuing an OTT
 *       under any {@link OttIntent}. Handles user lookup (with the
 *       existence-oracle defence), {@code TenantScope} binding, token
 *       generation, email dispatch, intent-specific issued-event emission,
 *       and the transactional boundary in one place.
 *   <li>{@link OttCompletionService} — the surface for marking an OTT
 *       journey complete. {@code markEmailVerified} flips
 *       {@code email_verified} (idempotent on already-verified) and emits
 *       {@code EmailVerifiedEvent}; {@code markPasswordResetCompleted}
 *       emits the journey-tail marker, with the password rotation itself
 *       owned by {@code TenantPasswordChangeFlow} in {@code auth.login}.
 *   <li>{@link OttIntentHandler} — the per-intent extension point. New
 *       intents add one {@link OttIntent} enum constant plus one
 *       {@code OttIntentHandler} bean; {@code OttDispatcher} discovers
 *       handlers via {@code List<OttIntentHandler>} (not a map keyed on
 *       enum, since Spring won't enum-coerce keys).
 * </ul>
 *
 * <p>Spring Modulith {@code @NamedInterface}; sibling sub-packages
 * ({@code auth.lockout}, {@code auth.login}) are independent — see each
 * one's own {@code package-info} for details.
 */
@NamedInterface("ott")
package com.stucray.limen.auth.ott;

import org.springframework.modulith.NamedInterface;
