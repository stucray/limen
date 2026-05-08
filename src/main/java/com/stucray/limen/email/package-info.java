/**
 * The {@code EmailSender} abstraction with {@code logging} and {@code smtp} drivers.
 *
 * <p>Other modules depend on the {@code EmailSender} interface to deliver
 * verification emails, password-reset links, and similar one-time-token messages.
 * The active driver is selected at runtime by the {@code limen.email.driver}
 * property: {@code logging} (default in tests; renders the email body to logs)
 * and {@code smtp} (Mailpit in dev, real SMTP in production) are the two
 * implementations.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.7 (Email infrastructure) for the driver-selection details, §4.15 (Package
 * structure) for the cross-cutting view.
 */
package com.stucray.limen.email;
