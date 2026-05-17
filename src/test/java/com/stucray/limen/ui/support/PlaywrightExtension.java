package com.stucray.limen.ui.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JUnit 5 extension that owns the Playwright lifecycle for UI tests.
 *
 * <p>One Playwright + Browser per JVM (expensive); a fresh BrowserContext + Page per test
 * (cheap, naturally isolated). Tracing runs on every test; on failure, the trace is
 * persisted and a final-state screenshot is captured. Both land under
 * {@code target/playwright-artifacts/<TestClass>/<testMethod>/} for CI artifact upload.
 *
 * <p>Tests get their {@link Page} via JUnit parameter injection — declare {@code Page page}
 * as a test method parameter.
 *
 * <p>Headless by default. Override locally with {@code -Dplaywright.headless=false}.
 *
 * <p>Engine defaults to Playwright's bundled Chromium. Override with
 * {@code -Dplaywright.browser=chromium|chrome|webkit|firefox}. {@code chrome} launches
 * Playwright's "chrome" channel — the system-installed stable Google Chrome — which
 * exhibits real-Chrome-only behaviour (e.g. the
 * {@code /.well-known/appspecific/com.chrome.devtools.json} workspace-folders probe)
 * that the bundled stripped Chromium does not. WebKit and Firefox catch
 * engine-rendering and cookie/CSRF differences. The bundled Chromium remains the
 * default so local {@code mvn verify} runtime is unchanged; CI runs the other engines
 * in a parallel matrix job over tests tagged {@code cross-browser}.
 */
public class PlaywrightExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(PlaywrightExtension.class);
    private static final String PAGE_KEY = "page";
    private static final String CONTEXT_KEY = "context";
    private static final String TRACE_PATH_KEY = "trace-path";
    private static final String SCREENSHOT_PATH_KEY = "screenshot-path";

    private static final Object LOCK = new Object();
    private static volatile Playwright playwright;
    private static volatile Browser browser;

    @Override
    public void beforeAll(ExtensionContext context) {
        ensureBrowserStarted();
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws IOException {
        ensureBrowserStarted();

        Path artifactDir = artifactDir(extensionContext);
        Files.createDirectories(artifactDir);

        BrowserContext browserContext = browser.newContext();
        browserContext.tracing().start(new Tracing.StartOptions()
            .setScreenshots(true)
            .setSnapshots(true)
            .setSources(false));
        Page page = browserContext.newPage();

        ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
        store.put(CONTEXT_KEY, browserContext);
        store.put(PAGE_KEY, page);
        store.put(TRACE_PATH_KEY, artifactDir.resolve("trace.zip"));
        store.put(SCREENSHOT_PATH_KEY, artifactDir.resolve("screenshot.png"));
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
        BrowserContext browserContext = store.get(CONTEXT_KEY, BrowserContext.class);
        Page page = store.get(PAGE_KEY, Page.class);
        Path tracePath = store.get(TRACE_PATH_KEY, Path.class);
        Path screenshotPath = store.get(SCREENSHOT_PATH_KEY, Path.class);

        boolean failed = extensionContext.getExecutionException().isPresent();
        try {
            if (failed) {
                browserContext.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
                if (page != null && !page.isClosed()) {
                    page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath).setFullPage(true));
                }
            } else {
                browserContext.tracing().stop();
            }
        } finally {
            if (browserContext != null) {
                browserContext.close();
            }
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == Page.class || type == BrowserContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
        Class<?> type = parameterContext.getParameter().getType();
        if (type == Page.class) {
            return store.get(PAGE_KEY, Page.class);
        }
        return store.get(CONTEXT_KEY, BrowserContext.class);
    }

    private static void ensureBrowserStarted() {
        if (browser != null) {
            return;
        }
        synchronized (LOCK) {
            if (browser != null) {
                return;
            }
            playwright = Playwright.create();
            playwright.selectors().setTestIdAttribute("data-test-action");
            boolean headless = !"false".equalsIgnoreCase(System.getProperty("playwright.headless", "true"));
            browser = launchBrowser(playwright, headless);
            Runtime.getRuntime().addShutdownHook(new Thread(PlaywrightExtension::closeBrowser, "playwright-shutdown"));
        }
    }

    private static Browser launchBrowser(Playwright playwright, boolean headless) {
        String engine = System.getProperty("playwright.browser", "chromium").toLowerCase();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless);
        return switch (engine) {
            case "chromium" -> playwright.chromium().launch(options);
            // The "chrome" channel hands off to the system-installed stable Google
            // Chrome rather than Playwright's bundled stripped Chromium. Required to
            // exercise behaviour the bundled build disables — workspace-folders
            // probe, ad-blocking, sync features. Fails fast if Chrome is not on
            // PATH; CI installs it explicitly.
            case "chrome" -> playwright.chromium().launch(options.setChannel("chrome"));
            case "webkit" -> playwright.webkit().launch(options);
            case "firefox" -> playwright.firefox().launch(options);
            default -> throw new IllegalArgumentException(
                "Unknown -Dplaywright.browser value: " + engine
                    + " (expected chromium, chrome, webkit, or firefox)");
        };
    }

    private static void closeBrowser() {
        try {
            if (browser != null) {
                browser.close();
            }
        } finally {
            if (playwright != null) {
                playwright.close();
            }
        }
    }

    private static Path artifactDir(ExtensionContext context) {
        String className = context.getRequiredTestClass().getSimpleName();
        String methodName = context.getRequiredTestMethod().getName();
        return Path.of("target", "playwright-artifacts", className, methodName);
    }
}
