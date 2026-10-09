package com.ibm.advocacy.collector;

import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests and Chrome cookie sharing, with a loopback workbook server only. */
@QuarkusTest
class SharePointDownloadTest {
    @Inject RuntimeWorkspace workspace;
    HttpServer server;URI base;SharePointCollector collector;byte[] workbook;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        collector=new SharePointCollector(){@Override public URI downloadUri(URI file){return base.resolve("/layout");}};
        collector.workspace=workspace;collector.allowedHost="127.0.0.1";collector.channel="chrome";collector.executable=Optional.empty();
        Files.deleteIfExists(workspace.path(".sessions/sharepoint-auth.json"));
        try(XSSFWorkbook xlsx=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){xlsx.createSheet("Inputs").createRow(0).createCell(0).setCellValue("Synthetic workbook");xlsx.write(out);workbook=out.toByteArray();}
    }
    @AfterEach void cleanup() throws Exception {server.stop(0);collector.close();Files.deleteIfExists(workspace.path(".sessions/sharepoint-auth.json"));}
    void endpoint(String path,int code,byte[] body) {
        server.createContext(path,exchange->{exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);exchange.close();});
    }
    @Test void restoredBrowserCookiesAuthenticateHttpDownloadAndRedirects() throws Exception {
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().set("Location","/file.xlsx");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/file.xlsx",exchange->{boolean signedIn=Optional.ofNullable(exchange.getRequestHeaders().getFirst("Cookie")).orElse("").contains("FedAuth=synthetic");byte[] body=signedIn?workbook:new byte[0];exchange.sendResponseHeaders(signedIn?200:401,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(Playwright playwright=Playwright.create()) {
            try(Browser chrome=collector.downloadBrowser(playwright);BrowserContext signIn=collector.downloadContext(chrome)) {
                signIn.addCookies(List.of(new Cookie("FedAuth","synthetic").setDomain("127.0.0.1").setPath("/").setHttpOnly(true)));collector.saveSession(signIn);
            }
            try(Browser chrome=collector.downloadBrowser(playwright);BrowserContext download=collector.downloadContext(chrome)) {
                assertArrayEquals(workbook,collector.requestWorkbook(download,base.resolve("/redirect")));
                assertEquals("VALIDATING",collector.session().get("downloadStage"));
            }
        }
    }
    @Test void httpErrorsAndHtmlResponsesAreDistinctFromValidWorkbooks() throws Exception {
        byte[] html="<html>A workbook viewer, not a download</html>".getBytes(StandardCharsets.UTF_8);
        endpoint("/html",200,html);endpoint("/denied",403,html);endpoint("/expired",401,html);endpoint("/missing",404,html);endpoint("/unavailable",503,html);endpoint("/oversize",200,new byte[6_000_001]);endpoint("/_layouts/15/Authenticate.aspx",200,html);server.start();
        try(Playwright playwright=Playwright.create();Browser chrome=collector.downloadBrowser(playwright);BrowserContext browser=collector.downloadContext(chrome)) {
            for(var entry:Map.of("/html",422,"/denied",403,"/expired",401,"/missing",404,"/unavailable",502,"/oversize",400,"/_layouts/15/Authenticate.aspx",401).entrySet()) {
                WebApplicationException error=assertThrows(WebApplicationException.class,()->collector.requestWorkbook(browser,base.resolve(entry.getKey())));
                assertEquals(entry.getValue(),error.getResponse().getStatus(),entry.getKey());
            }
        }
    }
    @Test void aViewerResponseWarmsTheOriginalLinkAndRetriesTheDirectDownload() throws Exception {
        AtomicBoolean redeemed=new AtomicBoolean();
        endpoint("/layout",200,"<html>Viewer</html>".getBytes(StandardCharsets.UTF_8));
        server.createContext("/warm",exchange->{redeemed.set("token=synthetic".equals(exchange.getRequestURI().getQuery()));exchange.getResponseHeaders().set("Set-Cookie","FedAuth=renewed; Path=/; HttpOnly");byte[] html="<html>Signed in</html>".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();});
        server.createContext("/file.xlsx",exchange->{boolean download=redeemed.get()&&"download=1".equals(exchange.getRequestURI().getQuery())&&Optional.ofNullable(exchange.getRequestHeaders().getFirst("Cookie")).orElse("").contains("FedAuth=renewed");byte[] body=download?workbook:new byte[0];exchange.sendResponseHeaders(download?200:403,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(Playwright playwright=Playwright.create();Browser chrome=collector.downloadBrowser(playwright);BrowserContext browser=collector.downloadContext(chrome)) {
            assertArrayEquals(workbook,collector.downloadWorkbook(browser,base.resolve("/warm?token=synthetic").toString(),base.resolve("/file.xlsx")));
            assertTrue(redeemed.get());
        }
    }
}
