package com.stucray.limen.email;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Mailpit (https://mailpit.axllent.org/) — a developer SMTP server with an HTTP
 * API for inspecting captured messages. Used by the SMTP integration tests so
 * the {@link SmtpEmailSender} round-trip can be verified end-to-end without
 * mocking {@code JavaMailSender}.
 *
 * <p>SMTP listens on 1025; the inspection API on 8025. Both are mapped to
 * ephemeral host ports; callers should read {@link #getMappedPort(int)}.
 */
public final class MailpitContainer extends GenericContainer<MailpitContainer> {

    public static final int SMTP_PORT = 1025;
    public static final int HTTP_API_PORT = 8025;

    public MailpitContainer() {
        super(DockerImageName.parse("axllent/mailpit:latest"));
        withExposedPorts(SMTP_PORT, HTTP_API_PORT);
        waitingFor(Wait.forHttp("/api/v1/info").forPort(HTTP_API_PORT));
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    public int httpApiPort() {
        return getMappedPort(HTTP_API_PORT);
    }

    public String httpApiBaseUrl() {
        return "http://" + getHost() + ":" + httpApiPort();
    }
}
