package com.ibm.advocacy.collector;

import com.ibm.advocacy.model.Measurement;
import com.ibm.advocacy.storage.RuntimeWorkspace;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.ibm.advocacy.model.MetricsSchema.*;

@ApplicationScoped
public class LinkedInCollector {
    @ConfigProperty(name="metrics.linkedin.enabled") boolean enabled;
    @Inject RuntimeWorkspace workspace;
    @Inject com.fasterxml.jackson.databind.ObjectMapper mapper;
    @ConfigProperty(name="metrics.browser-login-mode",defaultValue="LOCAL_BROWSER") String loginMode="LOCAL_BROWSER";
    @ConfigProperty(name="metrics.linkedin.browser-executable",defaultValue="") Optional<String> executable;
    private final ExecutorService loginWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean connecting=new AtomicBoolean();
    private volatile String sessionMessage="Not connected";
    @jakarta.annotation.PostConstruct void restore() throws Exception {
        if("LOCAL_HELPER".equals(loginMode)&&java.nio.file.Files.isRegularFile(workspace.path(".sessions/linkedin-auth.json")))sessionMessage="Connected. Saved session restored; collection checks that access is still valid.";
    }
    @jakarta.annotation.PreDestroy void close() { loginWorker.shutdownNow(); }
    public Map<String,Object> session() { return Map.of("enabled",enabled,"connecting",connecting.get(),"message",sessionMessage,"loginMode",loginMode); }
    public Map<String,Object> connect() {
        if(!enabled)throw new jakarta.ws.rs.BadRequestException("LinkedIn browser collection is disabled in configuration");
        if("LOCAL_HELPER".equals(loginMode)){sessionMessage="Use the local sign-in helper on your computer, then check the connection here.";return session();}
        if(!connecting.compareAndSet(false,true))throw new jakarta.ws.rs.ClientErrorException("A LinkedIn connection window is already open",409);
        sessionMessage="Sign in using the LinkedIn browser window";
        loginWorker.submit(()-> {
            synchronized(this) {
                try(Playwright playwright=Playwright.create();BrowserContext browser=playwright.chromium().launchPersistentContext(workspace.path(".sessions/linkedin"),new BrowserType.LaunchPersistentContextOptions().setHeadless(false))) {
                    Page page=browser.pages().isEmpty()?browser.newPage():browser.pages().getFirst();page.navigate("https://www.linkedin.com/login");
                    long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
                    while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()) {
                        if(page.url().startsWith("https://www.linkedin.com/feed")||page.url().startsWith("https://www.linkedin.com/company")) { workspace.bytes(workspace.path(".sessions/linkedin-auth.json"),browser.storageState().getBytes(java.nio.charset.StandardCharsets.UTF_8));sessionMessage="Connected. Retry the pending phase to collect fresh LinkedIn metrics.";return; }
                        page.waitForTimeout(1000);
                    }
                    sessionMessage="Sign-in timed out. Connect again or enter measurements manually.";
                } catch(Exception e) { sessionMessage="Browser sign-in unavailable. Install Chromium or enter measurements manually. "+e.getClass().getSimpleName(); }
                finally { connecting.set(false); }
            }
        });
        return session();
    }
    private Browser launch(Playwright playwright) {
        var options=new BrowserType.LaunchOptions().setHeadless(true);
        executable.filter(value->!value.isBlank()).ifPresent(value->options.setExecutablePath(java.nio.file.Path.of(value)));
        return playwright.chromium().launch(options);
    }
    public Map<String,Object> importSession(com.fasterxml.jackson.databind.JsonNode state) {
        if(!enabled||!"LOCAL_HELPER".equals(loginMode))throw new jakarta.ws.rs.BadRequestException("Remote browser sign-in is not enabled");
        String filtered=BrowserSessionState.filter(mapper,state,Set.of("linkedin.com","www.linkedin.com"),"li_at");
        if(!connecting.compareAndSet(false,true))throw new jakarta.ws.rs.ClientErrorException("LinkedIn is busy",409);
        sessionMessage="Verifying the local sign-in in the server browser…";
        loginWorker.submit(()->{synchronized(this){
            try(Playwright playwright=Playwright.create();Browser chrome=launch(playwright);BrowserContext browser=chrome.newContext(new Browser.NewContextOptions().setStorageState(filtered))) {
                Page page=browser.newPage();var response=page.navigate("https://www.linkedin.com/feed/",new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
                if(response==null||!response.ok()||!page.url().startsWith("https://www.linkedin.com/feed"))throw new IOException("Sign-in rejected");
                workspace.bytes(workspace.path(".sessions/linkedin-auth.json"),browser.storageState().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sessionMessage="Connected. Retry pending collection to collect fresh LinkedIn metrics.";
            }catch(Exception e){sessionMessage="The server could not verify the transferred LinkedIn session. Run the helper again or enter measurements manually.";}
            finally{connecting.set(false);}
        }});return session();
    }
    public synchronized Measurement collect(URI uri) throws Exception {
        if(!enabled)throw new IOException("LinkedIn needs an authenticated analytics session; enter a fresh "+MetricCollectors.type(uri.toString())+" measurement");
        boolean saved=java.nio.file.Files.isRegularFile(workspace.path(".sessions/linkedin-auth.json"));
        if(!saved&&!java.nio.file.Files.exists(workspace.path(".sessions/linkedin")))throw new IOException("Connect LinkedIn first, or enter a verified "+MetricCollectors.type(uri.toString())+" measurement manually");
        if(!uri.getPath().matches("/(events|posts|feed/update|pulse)/.*"))throw new IOException("Unsupported LinkedIn URL");
        try(Playwright playwright=Playwright.create();Browser chrome=saved?launch(playwright):null;BrowserContext browser=saved?chrome.newContext(new Browser.NewContextOptions().setStorageStatePath(workspace.path(".sessions/linkedin-auth.json"))):playwright.chromium().launchPersistentContext(workspace.path(".sessions/linkedin"),new BrowserType.LaunchPersistentContextOptions().setHeadless(true))) {
            Page page=browser.pages().isEmpty()?browser.newPage():browser.pages().getFirst();
            page.setDefaultTimeout(10000);page.navigate(uri.toString(),new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
            if(page.url().contains("login")||page.url().contains("authwall")||page.url().contains("checkpoint"))throw new IOException("LinkedIn login required; authenticate using the provided session launcher");
            String value,evidence;
            if(uri.getPath().startsWith("/events/")) {
                page.getByText("Show in-depth analytics",new Page.GetByTextOptions().setExact(false)).first().click();
                String analytics=page.locator("a[href*='post-analytics']").first().getAttribute("href");
                URI target=URI.create("https://www.linkedin.com").resolve(analytics);
                if(!Set.of("www.linkedin.com","linkedin.com").contains(target.getHost())||!target.getPath().contains("post-analytics"))throw new IOException("Invalid analytics link");
                page.navigate(target.toString(),new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.getByText("Video performance",new Page.GetByTextOptions().setExact(false)).first().waitFor();
                value=labelValue(page,"Video Views");evidence="LinkedIn post analytics → Video performance → Video Views: "+target;
            } else {
                page.locator("body").waitFor();
                String label=page.locator("button[aria-label*='reaction'], button[aria-label*='Reaction'], [class*='social-details-social-counts']").allTextContents().stream().filter(s->s.matches("(?s).*\\d.*")).findFirst().orElse("");
                Matcher match=Pattern.compile("(?i)([\\d,]+)\\s*(?:reactions?|$)").matcher(label.strip());
                if(!match.find())throw new IOException("LinkedIn reactions unavailable; manual total_reactions required");
                value=match.group(1).replace(",","");evidence="LinkedIn total reactions; comments/reposts excluded";
            }
            return new Measurement(value,MetricCollectors.type(uri.toString()),uri.toString(),Instant.now().toString(),evidence,false);
        }
    }
    static String labelValue(Page page,String label) throws IOException {
        Locator locator=page.getByText(label,new Page.GetByTextOptions().setExact(true)).first();locator.waitFor();
        String text=locator.locator("..").innerText();
        Matcher m=Pattern.compile("(?:^|\\s)([\\d,]+)(?:\\s|$)").matcher(text.replace(label," "));
        if(!m.find())throw new IOException("Exact Video Views value unavailable; use manual collection");
        return m.group(1).replace(",","");
    }
}
