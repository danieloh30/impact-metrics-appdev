package com.ibm.advocacy.collector;

import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real browser state round-trip with synthetic cookies; no IBM requests or credentials. */
@QuarkusTest
class SharePointSessionTest {
    @Inject RuntimeWorkspace workspace;
    @TempDir Path profiles;
    SharePointCollector collector;
    @BeforeEach void setup() throws Exception {
        collector=new SharePointCollector();collector.workspace=workspace;collector.allowedHost="ibm-my.sharepoint.com";collector.channel="chrome";collector.executable=java.util.Optional.empty();
        Files.deleteIfExists(workspace.path(".sessions/sharepoint-auth.json"));
    }
    @AfterEach void cleanup() throws Exception {
        Files.deleteIfExists(workspace.path(".sessions/sharepoint-auth.json"));collector.close();
    }
    BrowserContext launch(Playwright playwright,String name) {
        return playwright.chromium().launchPersistentContext(profiles.resolve(name),new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(true));
    }
    Cookie cookie(String name,String value,String domain) {
        return new Cookie(name,value).setDomain(domain).setPath("/").setSecure(true).setHttpOnly(true);
    }
    @Test void sessionCookiesSurviveClosingTheSignInBrowserAndOpeningTheDownloadBrowser() throws Exception {
        try(Playwright playwright=Playwright.create()) {
            try(BrowserContext signIn=launch(playwright,"sign-in")) {
                signIn.addCookies(List.of(cookie("FedAuth","synthetic-session","ibm-my.sharepoint.com")));
                assertEquals(-1,signIn.cookies().getFirst().expires);
                assertTrue(collector.authenticated(signIn));collector.saveSession(signIn);
            }
            // The download browser has no persistent profile: only the saved state can authenticate it.
            try(Browser chrome=collector.downloadBrowser(playwright);BrowserContext download=collector.downloadContext(chrome)) {
                assertTrue(collector.authenticated(download));
                assertEquals("synthetic-session",download.cookies().stream().filter(c->c.name.equals("FedAuth")).findFirst().orElseThrow().value);
                collector.saveSession(download);
            }
            try(Browser chrome=collector.downloadBrowser(playwright);BrowserContext nextDownload=collector.downloadContext(chrome)) {
                assertTrue(collector.authenticated(nextDownload));
            }
        }
    }
    @Test void missingStateAndCrossSiteRefreshCookiesDoNotConfirmSiteSignIn() throws Exception {
        try(Playwright playwright=Playwright.create()) {
            try(Browser chrome=collector.downloadBrowser(playwright);BrowserContext browser=collector.downloadContext(chrome)) {
                assertFalse(collector.authenticated(browser));
                browser.addCookies(List.of(cookie("FedAuth","unrelated","evil.example"),cookie("rtFa","cross-site-only",".sharepoint.com")));
                assertFalse(collector.authenticated(browser));collector.saveSession(browser);
            }
            try(Browser chrome=collector.downloadBrowser(playwright);BrowserContext browser=collector.downloadContext(chrome)) {
                assertFalse(collector.authenticated(browser),"A restored cross-site refresh cookie alone does not confirm site sign-in");
            }
        }
    }
}
