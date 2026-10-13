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
    @Inject WorkbookService workbooks;
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    final String url="https://ibm-my.sharepoint.com/:x:/r/personal/test_ibm_com/Documents/Inputs/Activity%20inputs.xlsx?web=1&e=opaque";
    final String historyUrl="https://ibm.sharepoint.com/:x:/r/sites/DEPT-DeveloperRelations/Shared%20Documents/Metrics.xlsx?web=1&e=opaque";
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    @Test void fileUrlsAreRestrictedAndDownloadUrlsPreserveBothSiteHosts() {
        for(String link:List.of(url,historyUrl)) {
            URI file=collector.fileUri(link);assertNull(file.getQuery());URI download=collector.downloadUri(file);assertEquals(file.getHost(),download.getHost());
            assertTrue(URLDecoder.decode(download.getRawQuery(),StandardCharsets.UTF_8).contains(file.toASCIIString()));
        }
        for(String invalid:List.of("http://ibm-my.sharepoint.com/sites/test/file.xlsx","https://evil.example/sites/test/file.xlsx","https://ibm.sharepoint.com.evil.example/sites/test/file.xlsx","https://user@ibm.sharepoint.com/sites/test/file.xlsx","https://ibm.sharepoint.com:8443/sites/test/file.xlsx","https://ibm.sharepoint.com/sites/test/../file.xlsx","https://ibm.sharepoint.com/sites/test/file.csv"))assertThrows(BadRequestException.class,()->collector.fileUri(invalid));
    }
    @Test void twoSnapshotsKeepSeparateSourcesAndNeverUseAvocadoHistory() throws Exception {
        int before=FakeSharePointCollector.downloads.get();var current=sources.inspect(url);byte[] currentBytes=FakeSharePointCollector.lastBytes.clone();var history=sources.inspect(historyUrl);byte[] historyBytes=FakeSharePointCollector.lastBytes.clone();
        var run=sources.create(current.sourceToken(),history.sourceToken(),"2026-10","October inputs");
        assertEquals(before+2,FakeSharePointCollector.downloads.get());assertEquals(1,run.currentRows);assertEquals(3,run.trailingRows);assertEquals(List.of("October inputs"),run.workbook.get("historySheets"));
        assertEquals("AppDev metrics.xlsx",run.workbook.get("historyFilename"));assertEquals(RuntimeWorkspace.hash(historyBytes),run.workbook.get("historySha256"));
        assertEquals(true,run.workbook.get("publicationCheckpoint"));
        assertArrayEquals(currentBytes,Files.readAllBytes(workspace.runPath(run.id,"rawdata/original.xlsx")));assertArrayEquals(historyBytes,Files.readAllBytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx")));
        var rows=workspace.data(run.id,"trailing-source").rows;assertEquals(List.of("August history","September history","October history"),rows.stream().map(r->r.get("Name of activity")).toList());assertTrue(rows.stream().allMatch(r->r.get("Source workbook").equals("AppDev metrics.xlsx")));
        assertFalse(run.workbook.get("historySourceUrl").toString().contains("?"));assertFalse(run.workbook.get("sourceUrl").toString().contains("?"));
        try(var files=Files.walk(workspace.runPath(run.id,""))){assertFalse(files.anyMatch(p->p.toString().endsWith(".csv")));}
        assertThrows(jakarta.ws.rs.ClientErrorException.class,()->sources.create(current.sourceToken(),history.sourceToken(),"2026-10","October inputs"));
    }
    @Test void monthSelectionChangesMonthlySheetsAndDateCutoffs() throws Exception {
        byte[] bytes=FakeSharePointCollector.history();assertEquals(List.of("September inputs"),workbooks.historySheets(bytes,"2026-09"));
        var result=workbooks.prepare(new com.ibm.advocacy.model.WorkbookRequest(new WorkbookImportTest().fixture(false),"2026-10","October inputs",workbooks.historySheets(bytes,"2026-10"),bytes),java.time.LocalDate.of(2026,10,31));
        assertEquals("2026-08-01",result.windowStart());assertEquals("2026-10-31",result.windowEnd());assertEquals(3,result.trailing().rows.size());
        assertThrows(BadRequestException.class,()->workbooks.historySheets(bytes,"2026-03"));
    }
    @Test void missingReportingSheetUsesLatestSnapshotAndNeverCombinesOverlappingRows() throws Exception {
        byte[] bytes=FakeSharePointCollector.history();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));var out=new java.io.ByteArrayOutputStream()) {
            book.removeSheetAt(book.getSheetIndex("October inputs"));book.write(out);byte[] withoutOctober=out.toByteArray();
            assertEquals(List.of("September inputs"),workbooks.historySheets(withoutOctober,"2026-10"));
            var result=workbooks.prepare(new com.ibm.advocacy.model.WorkbookRequest(new WorkbookImportTest().fixture(false),"2026-10","October inputs",workbooks.historySheets(withoutOctober,"2026-10"),withoutOctober));
            assertEquals(2,result.trailing().rows.size());assertTrue(result.trailing().rows.stream().allMatch(row->row.get("Source sheet").equals("September inputs")));
            var september=workbooks.prepare(new com.ibm.advocacy.model.WorkbookRequest(new WorkbookImportTest().fixture(false),"2026-09","Previous months",workbooks.historySheets(withoutOctober,"2026-09"),withoutOctober));
            assertEquals(3,september.trailing().rows.size());assertEquals("2026-07-01",september.windowStart());assertEquals("2026-09-30",september.windowEnd());
            book.setSheetHidden(book.getSheetIndex("September inputs"),true);out.reset();book.write(out);
            assertThrows(BadRequestException.class,()->workbooks.historySheets(out.toByteArray(),"2026-10"));
        }
    }
    @Test void endpointsRequireTwoIndependentSnapshotsAndLegacyImportsAreRemoved() throws Exception {
        given().get("/api/sharepoint/session").then().statusCode(200);given().contentType("application/json").body(Map.of("url",url)).post("/api/sharepoint/session").then().statusCode(202);
        given().contentType("application/json").body(Map.of("url","https://evil.example/file.xlsx")).post("/api/workbooks/from-sharepoint").then().statusCode(400);
        String current=given().contentType("application/json").body(Map.of("url",url)).post("/api/workbooks/from-sharepoint").then().statusCode(200).extract().path("sourceToken");
        String history=given().contentType("application/json").body(Map.of("url",historyUrl)).post("/api/workbooks/from-sharepoint").then().statusCode(200).extract().path("sourceToken");
        given().multiPart("sourceToken",current).multiPart("month","2026-10").multiPart("currentSheet","October inputs").post("/api/runs/from-sharepoint").then().statusCode(410);
        String id=given().multiPart("sourceToken",current).multiPart("historySourceToken",history).multiPart("month","2026-10").multiPart("currentSheet","October inputs").post("/api/runs/from-sharepoint").then().statusCode(200).extract().path("id");assertEquals(3,runs.get(id).trailingRows);
        for(String path:List.of("/api/runs","/api/runs/from-runtimes","/api/runs/from-workbook","/api/workbooks/inspect"))given().multiPart("current","old.csv","bad".getBytes()).post(path).then().statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(404),org.hamcrest.Matchers.is(405)));
    }
    @Test void existingTwoWorkbookRunWithoutPublicationFlagKeepsItsOriginalPhaseProgression() throws Exception {
        var current=sources.inspect(url);var past=sources.inspect(historyUrl);var run=sources.create(current.sourceToken(),past.sourceToken(),"2026-10","October inputs");
        // Saved runs imported before the publication feature have no checkpoint flag.
        // New-run publication and all seven phases are covered by WorkbookPublicationTest.
        run.workbook.remove("publicationCheckpoint");workspace.save(run);
        runs.start(run.id,true);
        for(int phase=1;phase<=4;phase++) {
            var ready=waitFor(run.id,"AWAITING_APPROVAL");assertEquals(phase,ready.phase);
            runs.approve(run.id,phase,ready.version,RunService.digest(ready),true);
        }
        var completed=waitFor(run.id,"COMPLETED");assertEquals(3,completed.artifacts.get("updated_90-day.csv").rows());assertEquals(1,completed.artifacts.get("current.csv").rows());
    }
    private com.ibm.advocacy.model.Run waitFor(String id,String status) throws Exception {
        for(int i=0;i<400;i++){var run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(25);}throw new AssertionError("Timed out waiting for "+status);
    }
    @Test void badSelectionDoesNotConsumeEitherSnapshotAndSameFileIsRejected() throws Exception {
        var current=sources.inspect(url);var history=sources.inspect(historyUrl);
        assertThrows(BadRequestException.class,()->sources.create(current.sourceToken(),history.sourceToken(),"2025-01","October inputs"));
        assertThrows(BadRequestException.class,()->sources.create(current.sourceToken(),current.sourceToken(),"2026-10","October inputs"));
        assertEquals(3,sources.create(current.sourceToken(),history.sourceToken(),"2026-10","October inputs").trailingRows);
    }@Test void linkedInPreparationCountsOnlySupportedHostsAndPathsAndBackfillsSavedRuns() throws Exception {
    var data=new com.ibm.advocacy.model.Data();for(String link:List.of("https://www.linkedin.com/posts/test/","https://linkedin.com/events/123/","https://www.linkedin.com/feed/update/urn:li:activity:1/","https://www.linkedin.com/pulse/article/","https://linkedin.com.evil.example/posts/test/","https://linkedin.com/in/member/","not a URL"))data.rows.add(new com.ibm.advocacy.model.Row(link,Map.of("Link / URL to resource",link)));
    assertEquals(4,RunService.linkedInRows(data));assertEquals(0,RunService.linkedInRows(null));
    var current=sources.inspect(url);var history=sources.inspect(historyUrl);var run=sources.create(current.sourceToken(),history.sourceToken(),"2026-10","October inputs");
    assertTrue(run.workbook.containsKey("linkedInCurrentRows"));assertTrue(run.workbook.containsKey("linkedInTrailingRows"));
    run.workbook.remove("linkedInCurrentRows");run.workbook.remove("linkedInTrailingRows");workspace.save(run);
    var restored=runs.get(run.id);assertTrue(restored.workbook.containsKey("linkedInCurrentRows"));assertTrue(restored.workbook.containsKey("linkedInTrailingRows"));
}

}
