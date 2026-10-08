package com.ibm.advocacy.service;

import com.ibm.advocacy.collector.SharePointCollector;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class SharePointImportTest {
    @Inject SharePointCollector collector;
    @Inject WorkbookSourceService sources;
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    final String url="https://ibm-my.sharepoint.com/:x:/r/personal/test_ibm_com/Documents/Inputs/Activity%20inputs.xlsx?web=1&e=opaque";
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    @Test void fileUrlIsRestrictedAndDownloadUrlPreservesTheSite() {
        URI file=collector.fileUri(url);assertEquals("/personal/test_ibm_com/Documents/Inputs/Activity inputs.xlsx",file.getPath());assertNull(file.getQuery());
        URI download=collector.downloadUri(file);assertEquals("/personal/test_ibm_com/_layouts/15/download.aspx",download.getPath());
        assertTrue(URLDecoder.decode(download.getRawQuery(),StandardCharsets.UTF_8).contains(file.toASCIIString()));
        for(String invalid:List.of("http://ibm-my.sharepoint.com/sites/test/file.xlsx","https://evil.example/sites/test/file.xlsx","https://ibm-my.sharepoint.com.evil.example/sites/test/file.xlsx","https://user@ibm-my.sharepoint.com/sites/test/file.xlsx","https://ibm-my.sharepoint.com:8443/sites/test/file.xlsx","https://ibm-my.sharepoint.com/sites/test/../file.xlsx","https://ibm-my.sharepoint.com/sites/test/file.csv"))assertThrows(BadRequestException.class,()->collector.fileUri(invalid));
    }
    @Test void inspectionAndPreparationReuseOneSnapshotAndKeepSourceProvenance() throws Exception {
        int before=FakeSharePointCollector.downloads.get();var inspection=sources.inspect(url);assertEquals("SharePoint activities.xlsx",inspection.filename());
        var run=sources.create(inspection.sourceToken(),"2026-10","October inputs",List.of("Previous months"));
        assertEquals(before+1,FakeSharePointCollector.downloads.get());assertEquals("SharePoint",run.workbook.get("source"));assertEquals(8,run.trailingRows);
        assertFalse(run.workbook.get("sourceUrl").toString().contains("opaque"));assertFalse(run.workbook.get("sourceUrl").toString().contains("?"));
        assertArrayEquals(FakeSharePointCollector.lastBytes,Files.readAllBytes(workspace.runPath(run.id,"rawdata/original.xlsx")));
        assertThrows(jakarta.ws.rs.ClientErrorException.class,()->sources.create(inspection.sourceToken(),"2026-10","October inputs",List.of()));
    }
    @Test void endpointsValidateLinksAndPrepareTheDownloadedWorkbook() throws Exception {
        given().get("/api/sharepoint/session").then().statusCode(200);
        given().contentType("application/json").body(Map.of("url",url)).post("/api/sharepoint/session").then().statusCode(202);
        given().contentType("application/json").body(Map.of("url","https://evil.example/file.xlsx")).post("/api/workbooks/from-sharepoint").then().statusCode(400);
        given().contentType("application/json").body("{}").post("/api/workbooks/from-sharepoint").then().statusCode(400);
        String token=given().contentType("application/json").body(Map.of("url",url)).post("/api/workbooks/from-sharepoint").then().statusCode(200).extract().path("sourceToken");
        String id=given().multiPart("sourceToken",token).multiPart("month","2026-10").multiPart("currentSheet","October inputs").multiPart("historySheets","[\"Previous months\"]").post("/api/runs/from-sharepoint").then().statusCode(200).extract().path("id");
        assertEquals("SharePoint",runs.get(id).workbook.get("source"));
        given().multiPart("sourceToken","unknown").post("/api/runs/from-sharepoint").then().statusCode(410);
    }
    @Test void badMonthDoesNotConsumeTheSnapshot() throws Exception {
        var inspection=sources.inspect(url);
        assertThrows(BadRequestException.class,()->sources.create(inspection.sourceToken(),"2025-01","October inputs",List.of()));
        assertEquals(1,sources.create(inspection.sourceToken(),"2026-10","October inputs",List.of()).currentRows);
    }
}
