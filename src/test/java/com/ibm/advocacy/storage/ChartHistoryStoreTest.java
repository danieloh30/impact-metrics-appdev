package com.ibm.advocacy.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.advocacy.collector.SharePointCollector;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.service.ImpactTrends;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ChartHistoryStoreTest {
    @Inject ChartHistoryStore store;
    @Inject RuntimeWorkspace workspace;
    @Inject ObjectMapper mapper;
    @Inject ImpactTrends trends;
    @Inject SharePointCollector collector;
    String url(String suffix){return "https://ibm.sharepoint.com/sites/DEPT-DeveloperRelations/Shared%20Documents/Advocacy%20RUN%20metrics%20"+suffix+"%202026.xlsx";}
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(java.nio.file.Path.of("runtimes",name)));}
    @Test void persistedTablesReloadWithoutDownloadingAndSurviveRunHistoryReset() throws Exception {
        String url=url("persistent");var saved=store.read(url,null);
        assertEquals("2025-09",saved.snapshot().channel().months().getFirst());assertEquals("100",saved.snapshot().channel().series().getFirst().values().getFirst());
        ChartHistoryStore restarted=new ChartHistoryStore();restarted.workspace=workspace;restarted.mapper=mapper;restarted.trends=trends;restarted.collector=collector;
        int downloads=com.ibm.advocacy.service.FakeSharePointCollector.downloads.get();assertEquals(saved.snapshot(),restarted.cached(url).snapshot());assertEquals(downloads,com.ibm.advocacy.service.FakeSharePointCollector.downloads.get());
        workspace.resetRuns();assertEquals(saved.snapshot(),restarted.cached(url).snapshot());
        given().queryParam("url",url).get("/api/chart-history").then().statusCode(200).body("snapshot.channel.months[0]",equalTo("2025-09")).body("source.storage",equalTo("Persistent runtime file cache"));
    }
    @Test void refreshKeepsPriorRunSnapshotAndFailedRefreshKeepsTheSavedSource() throws Exception {
        String url=url("refresh");var first=store.read(url,null);Run run=new Run();run.id=UUID.randomUUID().toString();store.attach(run,String.valueOf(first.source().get("cacheId")));
        var metadata=(Map<?,?>)run.workbook.get("chartHistory");byte[] frozen=Files.readAllBytes(workspace.runPath(run.id,"rawdata/"+metadata.get("snapshotFilename")));
        byte[] updated;try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(frozen));ByteArrayOutputStream out=new ByteArrayOutputStream()){book.getSheet("AppDev Advocacy").getRow(1).getCell(1).setCellValue(1234);book.write(out);updated=out.toByteArray();}
        var second=store.save(updated,"Advocacy RUN metrics 2026.xlsx",url,url,null);assertNotEquals(first.source().get("cacheId"),second.source().get("cacheId"));assertEquals("1234",store.cached(url).snapshot().channel().series().getFirst().values().getFirst());assertArrayEquals(frozen,Files.readAllBytes(workspace.runPath(run.id,"rawdata/"+metadata.get("snapshotFilename"))));
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->store.save(new byte[]{1},"invalid.xlsx",url,url,null));assertEquals(second.source().get("cacheId"),store.cached(url).source().get("cacheId"));
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->store.load("../../outside"));assertThrows(jakarta.ws.rs.BadRequestException.class,()->store.cached("https://example.com/private.xlsx"));
    }
    @Test void startupApiReadsAndAttachesSavedHistoryBeforeAgentsRun() throws Exception {
        String url=url("initial");String id=given().contentType("application/json").body(Map.of("url",url)).post("/api/chart-history").then().statusCode(200).body("snapshot.tool.months[12]",equalTo("2026-09")).extract().path("source.cacheId");
        var sources=io.quarkus.arc.Arc.container().instance(com.ibm.advocacy.service.WorkbookSourceService.class).get();
        String current="https://ibm-my.sharepoint.com/personal/alex/Documents/current.xlsx",history="https://ibm.sharepoint.com/sites/DEPT-DeveloperRelations/Shared%20Documents/history.xlsx";
        var now=sources.inspect(current);var old=sources.inspect(history);
        Run run=sources.create(now.sourceToken(),old.sourceToken(),"2026-10",now.suggestedCurrentSheet(),id);assertEquals("READY",run.status);var metadata=(Map<?,?>)run.workbook.get("chartHistory");assertEquals(id,metadata.get("cacheId"));assertTrue(Files.isRegularFile(workspace.runPath(run.id,"rawdata/"+metadata.get("snapshotFilename"))));
    }
}
