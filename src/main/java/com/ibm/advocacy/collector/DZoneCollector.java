package com.ibm.advocacy.collector;

import com.ibm.advocacy.model.Measurement;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Reads the article's own public counter, never related articles or reaction counts. */
@ApplicationScoped
public class DZoneCollector {
    @ConfigProperty(name="metrics.dzone.browser-channel",defaultValue="chrome") String channel="chrome";
    @ConfigProperty(name="metrics.dzone.browser-executable") Optional<String> executable=Optional.empty();
    @ConfigProperty(name="metrics.dzone.headless",defaultValue="true") boolean headless=true;
    private final ThreadLocal<Session> sessions=new ThreadLocal<>();
    public boolean backgroundMode() { return headless; }
    private static final Pattern COUNTER=Pattern.compile("(?i)^\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)\\s*([KMB]?)\\s+Views\\s*$");
    record Count(String value,String label,boolean approximate) {}

    static boolean article(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())&&uri.getUserInfo()==null&&uri.getPort()==-1
            &&Set.of("dzone.com","www.dzone.com").contains(Optional.ofNullable(uri.getHost()).orElse("").toLowerCase(Locale.ROOT))
            &&Optional.ofNullable(uri.getPath()).orElse("").matches("/articles/[A-Za-z0-9_-]+/?");
    }
    static Count counter(String label) throws IOException {
        Matcher match=COUNTER.matcher(label.replace('\u00a0',' '));
        if(!match.matches())throw new IOException("DZone article Views counter is missing or invalid; retry collection or enter views manually");
        BigDecimal value=new BigDecimal(match.group(1).replace(",",""));
        String suffix=match.group(2).toUpperCase(Locale.ROOT);
        if(suffix.isEmpty()&&value.stripTrailingZeros().scale()>0)throw new IOException("DZone exact Views counter must be an integer");
        value=value.multiply(switch(suffix){case "K"->BigDecimal.valueOf(1000);case "M"->BigDecimal.valueOf(1000000);case "B"->BigDecimal.valueOf(1000000000);default->BigDecimal.ONE;});
        if(value.stripTrailingZeros().scale()>0||value.compareTo(new BigDecimal("1000000000000000"))>0)throw new IOException("DZone Views counter is out of range");
        return new Count(value.stripTrailingZeros().toPlainString(),label.strip(),!suffix.isEmpty());
    }
    Measurement read(Page page,URI uri) throws IOException {
        if(!article(uri))throw new IOException("DZone collection supports public /articles/ URLs only");
        try {
            Response response=page.navigate(uri.toString(),new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
            if(response==null||response.status()!=200)throw new IOException("DZone "+(headless?"background collection blocked or unavailable":"blocked or unavailable")+" (HTTP "+(response==null?"unknown":response.status())+"); retry later or enter verified views manually. No visible browser fallback is opened.");
            if(!article(URI.create(page.url())))throw new IOException("DZone redirected away from a supported article");
            Locator label=page.locator("#activity-view-container .view-count");
            label.first().waitFor(new Locator.WaitForOptions().setTimeout(10000));
            if(label.count()!=1||page.locator("h1").count()==0)throw new IOException("DZone article counter is ambiguous or unavailable");
            Count count=counter(label.innerText());
            // Prefer an exact counter tooltip if the publisher exposes one.
            String title=label.getAttribute("title");
            if(title!=null)try {Count exact=counter(title);if(!exact.approximate())count=exact;}catch(IOException ignored) {}
            String evidence="DZone article Views counter: "+count.label()+(count.approximate()?"; approximate abbreviated public display expanded to "+count.value():"; exact public count");
            return new Measurement(count.value(),"VIEWS",uri.toString(),Instant.now().toString(),evidence,false);
        } catch(PlaywrightException e) {
            throw new IOException("DZone browser could not read the article Views counter; retry collection or enter views manually",e);
        }
    }
    /** A phase owns its browser on one worker thread; nothing launches until the first DZone URL. */
    public AutoCloseable batch() {
        if(sessions.get()!=null)throw new IllegalStateException("DZone collection batch already active on this worker");
        Session session=new Session();sessions.set(session);
        return ()->{try {session.close();}finally {sessions.remove();}};
    }
    Browser launch(Playwright playwright) {
        var options=new BrowserType.LaunchOptions().setHeadless(headless);
        if(executable.isPresent())options.setExecutablePath(Path.of(executable.get()));
        else if(!channel.isBlank())options.setChannel(channel);
        return playwright.chromium().launch(options);
    }
    void restrict(BrowserContext context) {
        Set<String> checked=new HashSet<>();
        context.route("**/*",route->{
            try {
                URI target=URI.create(route.request().url());String host=Optional.ofNullable(target.getHost()).orElse("").toLowerCase(Locale.ROOT);
                boolean allowed=host.equals("dzone.com")||host.endsWith(".dzone.com")||host.equals("challenges.cloudflare.com");
                if(!"https".equalsIgnoreCase(target.getScheme())||target.getUserInfo()!=null||target.getPort()!=-1||!allowed){route.abort();return;}
                if(!checked.contains(host))for(InetAddress address:InetAddress.getAllByName(host)) {
                    if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())throw new IOException("Private browser address rejected");
                }
                checked.add(host);route.resume();
            }catch(Exception rejected){route.abort();}
        });
    }
    private class Session implements AutoCloseable {
        Playwright playwright;Browser chrome;BrowserContext context;Page page;boolean closed;
        Measurement collect(URI uri) throws IOException {
            if(closed)throw new IOException("DZone collection browser unavailable for this phase; retry later or enter verified views manually");
            try {
                if(page==null) {
                    try {
                        playwright=Playwright.create();chrome=launch(playwright);
                        context=chrome.newContext(new Browser.NewContextOptions().setAcceptDownloads(false).setServiceWorkers(com.microsoft.playwright.options.ServiceWorkerPolicy.BLOCK));
                        restrict(context);page=context.newPage();
                    } catch(RuntimeException failure) {close();throw failure;}
                }
                return read(page,uri);
            } catch(PlaywrightException e) {
                throw new IOException("DZone Chrome collection unavailable; install Chrome or configure metrics.dzone.browser-executable, then retry",e);
            }
        }
        @Override public void close() {
            if(closed)return;closed=true;
            try {if(context!=null)context.close();}
            finally {try {if(chrome!=null)chrome.close();}finally {if(playwright!=null)playwright.close();}}
        }
    }
    public Measurement collect(URI uri) throws IOException {
        if(!article(uri))throw new IOException("DZone collection supports public /articles/ URLs only");
        Session session=sessions.get();
        if(session!=null)return session.collect(uri);
        try(Session standalone=new Session()) {return standalone.collect(uri);}
    }
}
