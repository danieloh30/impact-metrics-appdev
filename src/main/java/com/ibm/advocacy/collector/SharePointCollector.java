package com.ibm.advocacy.collector;

import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.RequestOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local, interactive sign-in. Passwords and browser cookies never enter model prompts or API responses. */
@ApplicationScoped
public class SharePointCollector {
    @Inject RuntimeWorkspace workspace;
    @ConfigProperty(name="metrics.sharepoint.enabled",defaultValue="true") boolean enabled;
    @ConfigProperty(name="metrics.sharepoint.host",defaultValue="ibm-my.sharepoint.com") String allowedHost;
    @ConfigProperty(name="metrics.sharepoint.site-host",defaultValue="ibm.sharepoint.com") String siteHost="ibm.sharepoint.com";
    private boolean allowed(String host){return host!=null&&(allowedHost.equalsIgnoreCase(host)||siteHost.equalsIgnoreCase(host));}
    @ConfigProperty(name="metrics.sharepoint.browser-channel",defaultValue="chrome") String channel;
    @ConfigProperty(name="metrics.sharepoint.browser-executable",defaultValue="") Optional<String> executable;
    private final ExecutorService browserWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile String status="NOT_CONNECTED",message="Connect SharePoint to sign in with your IBM account.";
    private volatile String downloadStage="IDLE";
    public void inspectionStage(String stage){downloadStage=stage;}
    public record Workbook(byte[] bytes,String filename,String sourceUrl) {}
    @jakarta.annotation.PreDestroy void close(){browserWorker.shutdownNow();}
    public Map<String,Object> session(){return Map.of("enabled",enabled,"busy",busy.get(),"status",status,"message",message,"host",allowedHost,"downloadStage",downloadStage);}

    public URI fileUri(String value) {
        try {
            URI uri=URI.create(value.strip());
            if(!"https".equalsIgnoreCase(uri.getScheme())||!allowed(uri.getHost())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IllegalArgumentException();
            String path=uri.getPath();if(path.startsWith("/:x:/r/"))path=path.substring(6);
            if(!path.matches("/(personal|sites|teams)/[^/]+/.+\\.xlsx")||Arrays.asList(path.split("/")).contains("..")||path.contains("\\"))throw new IllegalArgumentException();
            return new URI("https",null,uri.getHost().toLowerCase(Locale.ROOT),-1,path,null,null);
        }catch(Exception e){throw new BadRequestException("Use an HTTPS .xlsx file link from "+allowedHost+" or "+siteHost+" (the /:x:/r/ link or direct file path).");}
    }
    public URI downloadUri(URI file) {
        String[] segments=file.getPath().split("/");String site="/"+segments[1]+"/"+segments[2];
        return URI.create("https://"+file.getHost()+site+"/_layouts/15/download.aspx?SourceUrl="+URLEncoder.encode(file.toASCIIString(),StandardCharsets.UTF_8));
    }
    private BrowserType.LaunchPersistentContextOptions options(boolean headless) throws Exception {
        var options=new BrowserType.LaunchPersistentContextOptions().setHeadless(headless).setAcceptDownloads(true).setDownloadsPath(workspace.path(".uploads/sharepoint-downloads"));
        if(executable.isPresent()&&!executable.get().isBlank())options.setExecutablePath(Path.of(executable.get()));
        else if(!channel.isBlank())options.setChannel(channel);
        return options;
    }
    boolean authenticated(BrowserContext browser) {
        return authenticated(browser,allowedHost)||authenticated(browser,siteHost);
    }
    boolean authenticated(BrowserContext browser,String host) {
        return browser.cookies().stream().anyMatch(c->c.name.equals("FedAuth")&&(c.domain.equals(host)||host.endsWith(c.domain.startsWith(".")?c.domain:c.domain+"."))&&!c.value.isBlank());
    }
    // Chrome does not reliably retain session cookies when the sign-in process closes.
    // Snapshot them explicitly before closing and restore them in the download process.
    void saveSession(BrowserContext browser) throws Exception {
        workspace.bytes(workspace.path(".sessions/sharepoint-auth.json"),browser.storageState(new BrowserContext.StorageStateOptions().setIndexedDB(true)).getBytes(StandardCharsets.UTF_8));
    }
    Browser downloadBrowser(Playwright playwright) throws Exception {
        var options=new BrowserType.LaunchOptions().setHeadless(true).setDownloadsPath(workspace.path(".uploads/sharepoint-downloads"));
        if(executable.isPresent()&&!executable.get().isBlank())options.setExecutablePath(Path.of(executable.get()));
        else if(!channel.isBlank())options.setChannel(channel);
        return playwright.chromium().launch(options);
    }
    BrowserContext downloadContext(Browser browser) throws Exception {
        var options=new Browser.NewContextOptions().setAcceptDownloads(true);
        Path state=workspace.path(".sessions/sharepoint-auth.json");
        if(Files.isRegularFile(state))options.setStorageStatePath(state);
        return browser.newContext(options);
    }
    public Map<String,Object> connect(String url) {
        URI file=fileUri(url);if(!enabled)throw new BadRequestException("SharePoint import is disabled");
        if(!busy.compareAndSet(false,true))throw new ClientErrorException("SharePoint browser is busy. Finish sign-in or wait for the import.",409);
        status="CONNECTING";message="Sign in in the SharePoint browser window, including IBM SSO/MFA if requested.";
        browserWorker.submit(()->{
            try(Playwright playwright=Playwright.create();BrowserContext browser=playwright.chromium().launchPersistentContext(workspace.path(".sessions/sharepoint"),options(false))) {
                Page page=browser.pages().isEmpty()?browser.newPage():browser.pages().getFirst();
                page.navigate(URI.create(url.strip()).toASCIIString(),new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60_000));
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
                while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()) {
                    if(authenticated(browser,file.getHost())){saveSession(browser);status="CONNECTED";message="Signed in. Read each SharePoint workbook separately; both use this saved session.";return;}
                    page.waitForTimeout(1000);
                }
                status="NOT_CONNECTED";message="Sign-in timed out. Connect again.";
            }catch(Exception e){status="NOT_CONNECTED";message="Browser sign-in could not finish. Check Chrome installation, IBM access, and sign-in requirements, then reconnect.";}
            finally{busy.set(false);}
        });return session();
    }
    /** Uses the browser cookie jar but reads the response instead of waiting for a download event. */
    byte[] requestWorkbook(BrowserContext browser,URI target) {
        downloadStage="DOWNLOADING";
        APIResponse response;
        try{response=browser.request().get(target.toASCIIString(),RequestOptions.create().setTimeout(30_000).setFailOnStatusCode(false));}
        catch(PlaywrightException e){throw new WebApplicationException("SharePoint file request could not finish. Check your network and try reading the workbook again.",504);}
        try{return workbookBytes(response);}finally{response.dispose();}
    }
    byte[] workbookBytes(APIResponse response) {
        downloadStage="VALIDATING";
        int code=response.status();
        if(code==401)throw new ClientErrorException("SharePoint rejected the saved sign-in (HTTP 401). Reconnect SharePoint, then read the workbook again.",401);
        if(code==403)throw new ClientErrorException("SharePoint denied workbook download (HTTP 403). Check that your IBM account has download permission or whether an organizational policy blocks it.",403);
        if(code==404)throw new ClientErrorException("SharePoint could not find the workbook (HTTP 404). Check the file link and try again.",404);
        if(!response.ok())throw new WebApplicationException("SharePoint file request returned HTTP "+code+". Try again or verify the file in SharePoint.",502);
        byte[] bytes=response.body();
        if(bytes.length>6_000_000)throw new BadRequestException("SharePoint workbook must be smaller than 6 MB");
        if(bytes.length>=4&&bytes[0]=='P'&&bytes[1]=='K'&&bytes[2]==3&&bytes[3]==4)return bytes;
        URI finalUrl=URI.create(response.url());String path=Optional.ofNullable(finalUrl.getPath()).orElse("").toLowerCase(Locale.ROOT);
        if(!allowed(finalUrl.getHost())||path.contains("authenticate.aspx")||path.contains("login.aspx"))throw new ClientErrorException("SharePoint redirected the file request to sign-in. Reconnect SharePoint before importing.",401);
        throw new ClientErrorException("SharePoint returned a web page instead of an Excel file. Verify that the link opens the original workbook and that download is permitted.",422);
    }
    byte[] downloadWorkbook(BrowserContext browser,String originalUrl,URI file) {
        try{return requestWorkbook(browser,downloadUri(file));}
        catch(WebApplicationException first) {
            int code=first.getResponse().getStatus();
            if(code!=401&&code!=403&&code!=404&&code!=422)throw first;
            // Redeem the original sharing link and allow the saved IBM SSO session to refresh.
            downloadStage="REFRESHING";
            Page page=browser.newPage();
            try{page.navigate(URI.create(originalUrl.strip()).toASCIIString(),new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));}
            catch(PlaywrightException e){throw new WebApplicationException("SharePoint could not open the workbook link to refresh access. Check your network or reconnect SharePoint.",504);}
            try{return requestWorkbook(browser,downloadUri(file));}catch(WebApplicationException retry){int retryCode=retry.getResponse().getStatus();if(retryCode!=401&&retryCode!=403&&retryCode!=404&&retryCode!=422)throw retry;}
            URI direct=URI.create(file.toASCIIString()+"?download=1");
            return requestWorkbook(browser,direct);
        }
    }
    public Workbook download(String url) throws Exception {
        URI file=fileUri(url);if(!enabled)throw new BadRequestException("SharePoint import is disabled");
        if(!busy.compareAndSet(false,true))throw new ClientErrorException("SharePoint browser is busy. Finish sign-in first.",409);
        downloadStage="OPENING";
        Future<Workbook> future=browserWorker.submit(()->{
            try(Playwright playwright=Playwright.create();Browser chrome=downloadBrowser(playwright);BrowserContext browser=downloadContext(chrome)) {
                if(!authenticated(browser))throw new ClientErrorException("Connect SharePoint and sign in before importing the workbook.",401);
                byte[] bytes=downloadWorkbook(browser,url,file);
                String filename=file.getPath().substring(file.getPath().lastIndexOf('/')+1);
                saveSession(browser);
                status="CONNECTED";message="Workbook downloaded. Reading its activity sheets.";
                return new Workbook(bytes,filename,file.toASCIIString());
            }catch(PlaywrightException e){throw new WebApplicationException("SharePoint browser is unavailable. Install Chrome or configure the browser executable, then connect again.",503);}
        });
        try{return future.get(120,TimeUnit.SECONDS);}
        catch(ExecutionException e){downloadStage="FAILED";if(e.getCause() instanceof WebApplicationException client&&client.getResponse().getStatus()==401){status="NOT_CONNECTED";message="SharePoint sign-in expired or was rejected. Reconnect before importing.";}if(e.getCause() instanceof Exception cause)throw cause;throw e;}
        catch(TimeoutException e){downloadStage="FAILED";future.cancel(true);throw new WebApplicationException("SharePoint download timed out. Check your network and try again.",504);}
        finally{busy.set(false);}
    }
}
