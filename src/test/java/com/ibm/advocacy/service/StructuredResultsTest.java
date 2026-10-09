package com.ibm.advocacy.service;

import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.ResultStore;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class StructuredResultsTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject ResultStore results;
    @Inject Pipeline pipeline;
    @Inject CsvCodec csv;
    @BeforeEach void instructions() throws Exception {
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
    }
    void row(Sheet sheet,int n,String... cells) {
        var row=sheet.createRow(n);for(int i=0;i<cells.length;i++)row.createCell(i).setCellValue(cells[i]);
    }
    byte[] workbook() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet current=book.createSheet("Current month inputs"),history=book.createSheet("Previous months inputs");
            for(Sheet sheet:List.of(current,history))row(sheet,0,"Date of activity","Author","Tool","Type of activity","Name of activity",URL,VIEWS,"Duration (in hours)","Current total");
            row(current,1,"2026-10-02","Oh, Daniel\nÉlder","Quarkus;IBM Bob","Recorded video","Cells, not CSV; intact\nTitle","","1000","2","");
            row(current,2,"2026-10-03","Daniel","Quarkus","Customer meeting","Meeting","","10","2","");
            row(history,1,"2026-09-01","Élder","Quarkus","Recorded video","History","","10","2","50");
            book.write(out);return out.toByteArray();
        }
    }
    Run imported() throws Exception { return runs.createWorkbook(workbook(),"structured.xlsx","2026-10","Current month inputs",List.of("Previous months inputs")); }
    Run await(String id,String status) throws Exception {
        for(int n=0;n<300;n++){Run run=runs.get(id);if(run.status.equals(status)){Thread.sleep(25);return runs.get(id);}if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(25);}
        throw new AssertionError("Timed out waiting for "+status);
    }
    void approve(Run run) throws Exception {runs.approve(run.id,run.phase,run.version,RunService.digest(run),false);}
    void noCsv(String id) throws Exception {
        try(var paths=Files.walk(workspace.runPath(id,""))) {
            assertEquals(List.of(),paths.filter(Files::isRegularFile).filter(p->p.getFileName().toString().endsWith(".csv")).toList());
        }
    }
    Map<String,String> files(String id) throws Exception {
        Map<String,String> hashes=new TreeMap<>();Path root=workspace.runPath(id,"");
        try(var paths=Files.walk(root)) { for(Path path:paths.filter(Files::isRegularFile).toList())hashes.put(root.relativize(path).toString(),RuntimeWorkspace.hash(Files.readAllBytes(path))); }
        return hashes;
    }
    @Test void allSevenPhasesAndHistoricalReviewStoreOnlyStructuredVersions() throws Exception {
        Run run=imported();noCsv(run.id);assertEquals(run.workbook.get("sha256"),RuntimeWorkspace.hash(Files.readAllBytes(workspace.runPath(run.id,"rawdata/original.xlsx"))));
        runs.start(run.id,true);Run first=await(run.id,"AWAITING_APPROVAL");noCsv(run.id);
        Data initial=runs.artifactData(run.id,"digital.csv",first.version);assertEquals("Current month inputs:2",initial.rows.getFirst().id);
        assertEquals("Oh, Daniel\nÉlder",initial.rows.getFirst().get("Author of activity"));assertEquals("Cells, not CSV; intact\nTitle",initial.rows.getFirst().get("Name of activity"));
        String firstDigest=RunService.digest(first);approve(first);Run second=await(run.id,"AWAITING_APPROVAL");noCsv(run.id);approve(second);
        Run third=await(run.id,"AWAITING_APPROVAL");noCsv(run.id);assertEquals(4,runs.artifactData(run.id,"digital.csv",null).rows.size());approve(third);
        Run fourth=await(run.id,"AWAITING_APPROVAL");assertEquals(4,fourth.phase);noCsv(run.id);
        int oldHistoryVersion=fourth.artifacts.get("updated_90-day.csv").version();
        runs.reviewTrailingInput(run.id,fourth.version,workspace.data(run.id,"trailing-source").rows.getFirst().id,null,null,true,null);
        Run reviewed=await(run.id,"AWAITING_APPROVAL");noCsv(run.id);assertEquals("10",runs.artifactData(run.id,"updated_90-day.csv",null).rows.getFirst().get(VIEWS));
        assertEquals("",runs.artifactData(run.id,"updated_90-day.csv",oldHistoryVersion).rows.getFirst().get(VIEWS));approve(reviewed);
        Run complete=await(run.id,"COMPLETED");noCsv(run.id);assertEquals(4,complete.approvals.size());assertEquals(5,complete.artifacts.size());
        Data finalData=runs.artifactData(run.id,"current.csv",null);assertEquals(FINAL_HEADERS,finalData.headers);assertEquals(5,finalData.rows.size());
        assertEquals(4,finalData.rows.stream().filter(r->r.get(VIEWS).equals("250")).count());
        assertEquals(1,runs.artifactData(run.id,"digital.csv",first.version).rows.size());assertEquals(firstDigest,complete.approvals.getFirst().digest());
        for(Artifact artifact:complete.artifacts.values())assertEquals(artifact.sha256(),RuntimeWorkspace.hash(Files.readAllBytes(workspace.runPath(run.id,"output/"+artifact.version()+"/"+ResultStore.datasetName(artifact.name())+".json"))));
        assertEquals("10",workspace.data(run.id,"trailing-source").rows.getFirst().get(VIEWS));
    }
    @Test void previewsAndExplicitInputCsvResultCsvAndZipDownloadsCreateNoDiskFiles() throws Exception {
        Run run=imported();runs.start(run.id,true);Run phase=await(run.id,"AWAITING_APPROVAL");
        Map<String,String> before=files(run.id);String base="/api/runs/"+run.id;
        Map<String,Object> preview=given().queryParam("version",phase.version).get(base+"/artifacts/digital.csv/preview").then().statusCode(200).extract().as(Map.class);
        assertEquals("Current month inputs:2",((Map<?,?>)((List<?>)preview.get("rows")).getFirst()).get("id"));
        assertEquals(before,files(run.id));
        byte[] current=given().urlEncodingEnabled(false).get(base+"/inputs/AvocadoBot%202.csv/download").then().statusCode(200).contentType("text/csv; charset=utf-8").extract().asByteArray();
        assertEquals(2,csv.read(current,false).rows.size());
        byte[] history=given().get(base+"/inputs/trailing_90-day.csv/download").then().statusCode(200).extract().asByteArray();
        assertEquals("Élder",csv.read(history,true,StandardCharsets.UTF_8).rows.getFirst().get("Author"));
        byte[] digital=given().get(base+"/artifacts/digital.csv/download").then().statusCode(200).extract().asByteArray();
        assertEquals("Cells, not CSV; intact\nTitle",csv.read(digital,false).rows.getFirst().get("Name of activity"));
        byte[] zipBytes=given().get(base+"/download-all").then().statusCode(200).contentType("application/zip").extract().asByteArray();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            Map<String,byte[]> entries=new HashMap<>();for(ZipEntry entry;(entry=zip.getNextEntry())!=null;)entries.put(entry.getName(),zip.readAllBytes());
            assertEquals(Set.of("digital.csv","follow-up.csv"),entries.keySet());assertArrayEquals(digital,entries.get("digital.csv"));
        }
        noCsv(run.id);assertEquals(before,files(run.id));
        given().queryParam("offset",1).get(base+"/artifacts/digital.csv/preview").then().statusCode(200).body("rows.size()",org.hamcrest.Matchers.is(0));
        assertThrows(BadRequestException.class,()->runs.artifactData(run.id,"../secret.csv",null));
        assertThrows(BadRequestException.class,()->runs.artifactData(run.id,"digital.csv",0));
        assertThrows(NotFoundException.class,()->runs.artifactData(run.id,"current.csv",null));
    }
    @Test void olderCsvResultVersionsRemainReadableAndPreviewableWithoutRewriting() throws Exception {
        Run run=imported();Data source=workspace.data(run.id,"source");byte[] legacy=csv.write(source);
        run.version=1;run.artifacts.put("digital.csv",new Artifact("digital.csv",1,1,source.rows.size(),RuntimeWorkspace.hash(legacy)));
        workspace.bytes(workspace.runPath(run.id,"output/1/digital.csv"),legacy);workspace.save(run);
        Map<String,String> before=files(run.id);assertEquals(source.headers,runs.artifactData(run.id,"digital.csv",null).headers);
        assertArrayEquals(legacy,runs.artifact(run.id,"digital.csv",1));
        given().get("/api/runs/"+run.id+"/artifacts/digital.csv/preview").then().statusCode(200).body("total",org.hamcrest.Matchers.is(2));
        assertEquals(before,files(run.id));assertThrows(NotFoundException.class,()->results.load(run.id,"digital.csv",2));
    }
    @Test void interruptedOlderRunsResumeFromOlderJsonPhaseNamesAndPublishStructuredResults() throws Exception {
        Run run=imported();Data source=workspace.data(run.id,"source");
        workspace.data(run.id,"phase-1-digital.csv",Data.output(source,List.of(source.rows.getFirst())));
        workspace.data(run.id,"phase-1-follow-up.csv",Data.output(source,List.of()));
        run.phase=2;run.version=1;workspace.save(run);PhaseTask task=new PhaseTask(run.id,2);
        pipeline.attendance(task);pipeline.publish(task);
        Run updated=workspace.load(run.id);assertEquals(2,updated.version);assertEquals(1,runs.artifactData(run.id,"in-person.csv",null).rows.size());
        assertTrue(Files.exists(workspace.runPath(run.id,"state/phase-2-in-person.json")));noCsv(run.id);
    }
}
