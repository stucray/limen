package com.stucray.limen.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Hands the auto-configured Spring Boot OpenTelemetry SDK to the
 * {@link OpenTelemetryAppender} declared in {@code logback-spring.xml}.
 *
 * <p>Logback initializes before the Spring application context, so the
 * appender starts in a no-op buffering mode. Once the {@code OpenTelemetry}
 * bean is created, this installer wires the two together; the appender
 * drains its buffer and forwards subsequent events through the OTLP log
 * exporter.
 *
 * <p>Without this installer the OTLP logging endpoint stays configured but
 * zero log records reach Loki — the bridge has nowhere to send them.
 */
@Configuration
class OtelLogbackInstaller {

    private static final Logger log = LoggerFactory.getLogger(OtelLogbackInstaller.class);

    public OtelLogbackInstaller(OpenTelemetry openTelemetry) {
        OpenTelemetryAppender.install(openTelemetry);
        log.info("OpenTelemetry Logback appender installed; subsequent logs forward to OTLP.");
    }
}
