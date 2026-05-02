package com.stucray.limen.ui.support;

import com.stucray.limen.TestcontainersConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Base class for Playwright-driven UI tests.
 *
 * <p>Boots a real application instance with the existing Testcontainers Postgres,
 * picks a random port, and wires {@link PlaywrightExtension} so test methods can
 * declare a {@link com.microsoft.playwright.Page} parameter.
 *
 * <p>Subclasses use {@link #baseUrl()} to build absolute URLs — page objects should
 * receive it via constructor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(PlaywrightExtension.class)
public abstract class BaseUiIT {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestTenantFactory tenants;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
