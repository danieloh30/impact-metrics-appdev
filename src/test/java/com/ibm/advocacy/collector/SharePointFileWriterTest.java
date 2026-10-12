package com.ibm.advocacy.collector;

import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.WebApplicationException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real REST requests against a loopback server; never touches an IBM workbook. */
class SharePointFileWriterTest {
    HttpServer server;URI file;Playwright playwright;APIRequestContext requests;byte[] original,candidate,remote,draft;
    boolean checkedOut,denyUpload,corruptUpload;int checkoutFailure;String metadataOverride,errorBody="";int checkout,put,checkin,undo;
    byte[] workbook(String text) throws Exception {try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){book.createSheet("Inputs").createRow(0).createCell(0).setCellValue(text);book.write(out);return out.toByteArray();}}
    @BeforeEach void setup() throws Exception {
        original=workbook("Original");candidate=workbook("Updated");remote=original.clone();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);file=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/sites/test/Shared%20Documents/Workbook%20%23%25.xlsx");
        server.createContext("/sites/test/_api/",exchange->{String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();int status=200;byte[] body="{}".getBytes(StandardCharsets.UTF_8);
            if(!"FedAuth=synthetic".equals(exchange.getRequestHeaders().getFirst("Cookie"))){status=401;}
            else if(path.endsWith("contextinfo")){body="{\"d\":{\"GetContextWebInformation\":{\"FormDigestValue\":\"synthetic-digest\"}}}".getBytes(StandardCharsets.UTF_8);}
            else if(method.equals("POST")&&!"synthetic-digest".equals(exchange.getRequestHeaders().getFirst("X-RequestDigest"))){status=403;}
            else if(path.endsWith("/CheckOut()")){checkout++;if(checkoutFailure!=0){status=checkoutFailure;body=errorBody.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("SPRequestGuid","12345678-1234-1234-1234-123456789abc");}else{checkedOut=true;draft=remote.clone();}}
            else if(path.contains("CheckIn(")){checkin++;checkedOut=false;}
            else if(path.endsWith("UndoCheckOut()")){undo++;checkedOut=false;remote=draft.clone();}
            else if(path.endsWith("/$value")&&method.equals("POST")){put++;if(denyUpload)status=403;else if(!"PUT".equals(exchange.getRequestHeaders().getFirst("X-HTTP-Method")))status=400;else remote=corruptUpload?new byte[]{'P','K',3,4}:exchange.getRequestBody().readAllBytes();}
            else if(path.endsWith("/$value")){body=remote;}
            else body=("{\"d\":{\"CheckOutType\":"+(checkedOut?0:2)+"}}").getBytes(StandardCharsets.UTF_8);
            if(method.equals("GET")&&!path.endsWith("/$value")&&metadataOverride!=null)body=metadataOverride.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();playwright=Playwright.create();requests=playwright.request().newContext(new APIRequest.NewContextOptions().setExtraHTTPHeaders(Map.of("Cookie","FedAuth=synthetic")));
    }
    @AfterEach void stop(){requests.dispose();playwright.close();server.stop(0);}
    @Test void guardedCheckoutUploadVerificationAndCheckinUseCookiesAndAnExactBodyAndRetryIsIdempotent() throws Exception {
        var writer=new SharePointFileWriter(requests);assertArrayEquals(candidate,writer.replace(file,RuntimeWorkspace.hash(original),candidate));assertArrayEquals(candidate,remote);assertEquals(1,checkout);assertEquals(1,put);assertEquals(1,checkin);assertEquals(0,undo);assertFalse(checkedOut);assertArrayEquals(candidate,writer.replace(file,RuntimeWorkspace.hash(original),candidate));assertEquals(1,put);assertTrue(SharePointFileWriter.endpoint(file).toASCIIString().contains("%23%25"));
    }
    @Test void changedOrAlreadyCheckedOutFilesAreNotOverwritten() throws Exception {var writer=new SharePointFileWriter(requests);var changed=assertThrows(WebApplicationException.class,()->writer.replace(file,"old-hash",candidate));assertEquals(409,changed.getResponse().getStatus());checkedOut=true;var locked=assertThrows(WebApplicationException.class,()->writer.replace(file,RuntimeWorkspace.hash(original),candidate));assertEquals(409,locked.getResponse().getStatus());assertEquals(0,put);assertEquals(0,checkout);assertArrayEquals(original,remote);}
    @Test void deniedUploadRollsBackOnlyOurCheckoutAndPreservesThePublishedVersion() throws Exception {denyUpload=true;var error=assertThrows(WebApplicationException.class,()->new SharePointFileWriter(requests).replace(file,RuntimeWorkspace.hash(original),candidate));assertEquals(403,error.getResponse().getStatus());assertEquals(1,undo);assertEquals(0,checkin);assertArrayEquals(original,remote);assertFalse(checkedOut);}
    @Test void failedReadbackVerificationNeverCommitsAndRestoresTheOriginalVersion() throws Exception {corruptUpload=true;assertThrows(WebApplicationException.class,()->new SharePointFileWriter(requests).replace(file,RuntimeWorkspace.hash(original),candidate));assertEquals(1,undo);assertEquals(0,checkin);assertArrayEquals(original,remote);}    @Test void statusIsReadOnlyAndDistinguishesAbsentOwnersFromUnknownMetadata() throws Exception {
        metadataOverride="{\"d\":{\"CheckOutType\":2,\"LockedByUser\":null,\"CheckedOutByUser\":null,\"UIVersionLabel\":\"206.0\",\"TimeLastModified\":\"2026-10-12T15:38:01Z\"}}";
        var writer=new SharePointFileWriter(requests);var status=writer.status(file);
        assertEquals(2,status.checkoutType());assertTrue(status.lockOwnerReported());assertEquals("",status.lockedBy());assertTrue(status.checkoutOwnerReported());assertEquals("206.0",status.version());assertFalse(status.checkedAt().isBlank());
        metadataOverride="{\"d\":{\"CheckOutType\":0,\"LockedByUser\":{\"Title\":\"Test editor\"},\"CheckedOutByUser\":{\"Title\":\"Test checkout\"}}}";
        assertEquals("Test editor",writer.status(file).lockedBy());assertEquals("Test checkout",writer.status(file).checkedOutBy());
        metadataOverride="{\"d\":{}}";var unknown=writer.status(file);assertEquals(-1,unknown.checkoutType());assertFalse(unknown.lockOwnerReported());assertFalse(unknown.checkoutOwnerReported());
        assertEquals(0,checkout+put+checkin+undo);assertArrayEquals(original,remote);
    }
    @Test void checkoutConflictPreservesOperationAndServerDetailsWithoutCallingItALockOrReleasingAnotherCheckout() {
        checkoutFailure=409;errorBody="{\"error\":{\"code\":\"-2130575305, Microsoft.SharePoint.SPException\",\"message\":{\"value\":\"Checkout is not supported for this file.\"}}}";
        var failure=assertThrows(WebApplicationException.class,()->new SharePointFileWriter(requests).replace(file,RuntimeWorkspace.hash(original),candidate));
        assertEquals(409,failure.getResponse().getStatus());assertTrue(failure.getMessage().contains("checking out the workbook"));assertTrue(failure.getMessage().contains("HTTP 409"));assertTrue(failure.getMessage().contains("Checkout is not supported"));assertTrue(failure.getMessage().contains("Request ID: 12345678"));assertFalse(failure.getMessage().contains("locked by another editor"));assertEquals(0,put+checkin+undo);assertArrayEquals(original,remote);
    }
    @Test void protectedAuthenticationAndHtmlBodiesNeverEnterPublicFailureDetails() {
        checkoutFailure=423;errorBody="{\"error\":{\"message\":{\"value\":\"FedAuth=secret-test-token\"}}}";
        var failure=assertThrows(WebApplicationException.class,()->new SharePointFileWriter(requests).replace(file,RuntimeWorkspace.hash(original),candidate));
        assertTrue(failure.getMessage().contains("HTTP 423"));assertFalse(failure.getMessage().contains("secret-test-token"));assertTrue(failure.getMessage().contains("Protected authentication details omitted"));
        errorBody="<html>private-sign-in-page</html>";failure=assertThrows(WebApplicationException.class,()->new SharePointFileWriter(requests).replace(file,RuntimeWorkspace.hash(original),candidate));assertFalse(failure.getMessage().contains("private-sign-in-page"));assertEquals(0,put+checkin+undo);
    }

}
