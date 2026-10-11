package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.*;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.collector.MetricCollectors;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class ReportingPolicyTest {
    @Inject RunService runs;
    @Inject Pipeline pipeline;
    @Inject RuntimeWorkspace workspace;
    @Inject ResultStore results;
    @Inject CsvCodec csv;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    Run run() throws Exception {Run r=new Run();r.id=UUID.randomUUID().toString();r.createdAt=Instant.now().toString();r.phase=4;r.status="AWAITING_APPROVAL";r.trailing=true;workspace.save(r);return r;}
    Row row(String id,String views,String url){return new Row(id,Map.of(VIEWS,views,URL,url,"Current total","","Trailing 90-day","Yes","Type of activity","Recorded video","Name of activity",id));}
    @Test void previewsDownloadsAndZipUseCeilingWithoutMutatingOriginalSnapshots() throws Exception {
        Run r=run();r.version=1;Data data=new Data(List.of(VIEWS,"Current total","Original Views / attendance",NOTE,"Type of activity",URL,"Author"),List.of(new Row("one",Map.of(VIEWS,"166.5","Current total","1966.5","Original Views / attendance","0.6667",NOTE,"Only public HTTPS resource URLs are supported")),new Row("negative",Map.of(VIEWS,"-0.5",NOTE,"Negative delta — review"))));
        r.artifacts.put("updated_90-day.csv",results.save(r.id,"updated_90-day.csv",1,4,data));workspace.save(r);byte[] original=Files.readAllBytes(workspace.runPath(r.id,"output/1/updated_90-day.json"));
        given().get("/api/runs/"+r.id+"/artifacts/updated_90-day.csv/preview").then().statusCode(200).body("rows[0].cells.'Views / attendance'",org.hamcrest.Matchers.is("167"));
        Data download=csv.read(runs.artifact(r.id,"updated_90-day.csv",null),false);assertEquals("167",download.rows.getFirst().get(VIEWS));assertEquals("1966.5",download.rows.getFirst().get("Current total"));assertEquals("0.6667",download.rows.getFirst().get("Original Views / attendance"));assertFalse(download.rows.getFirst().get(NOTE).contains("Only public HTTPS"));assertEquals("0",download.rows.get(1).get(VIEWS));assertTrue(download.rows.get(1).get(NOTE).contains("Negative delta"));
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(runs.zip(r.id)))){assertEquals("updated_90-day.csv",zip.getNextEntry().getName());assertArrayEquals(runs.artifact(r.id,"updated_90-day.csv",null),zip.readAllBytes());}
        assertArrayEquals(original,Files.readAllBytes(workspace.runPath(r.id,"output/1/updated_90-day.json")));
    }
    @Test void legacyTrailingExportsAndFiltersUseCorrectedInterpretationWithoutRewritingSnapshots() throws Exception {
        Run r=run();r.version=1;r.workbook.put("historySha256","baseline-hash");
        Row delta=new Row("delta",Map.of(VIEWS,"433.5","Weight","0.5","Impact hours","999","Metric basis","Trailing delta","Name of activity","Measured article"));
        Row retained=new Row("keep",Map.of(VIEWS,"9000","Weight","0.8","Impact hours","7200","Metric basis","Workbook content input — not a trailing delta","Name of activity","Retained article"));
        Row unresolved=new Row("missing",Map.of(VIEWS,"","Metric basis","Unresolved trailing delta","Name of activity","Missing article"));
        r.artifacts.put("updated_90-day.csv",results.save(r.id,"updated_90-day.csv",1,4,Data.output(new Data(List.of("Type of activity",URL,"Author"),List.of()),List.of(delta,retained,unresolved))));workspace.save(r);
        Path snapshot=workspace.runPath(r.id,"output/1/updated_90-day.json");byte[] original=Files.readAllBytes(snapshot);
        String endpoint="/api/runs/"+r.id+"/artifacts/updated_90-day.csv/preview";
        given().queryParam("basis","growth").get(endpoint).then().statusCode(200).body("total",org.hamcrest.Matchers.is(1)).body("unfilteredTotal",org.hamcrest.Matchers.is(3)).body("rows[0].cells.'Impact hours'",org.hamcrest.Matchers.is("216.75")).body("reporting.exactGrowth",org.hamcrest.Matchers.is("433.5")).body("reporting.retainedRows",org.hamcrest.Matchers.is(1));
        given().queryParam("basis","retained").queryParam("q","Retained").get(endpoint).then().statusCode(200).body("total",org.hamcrest.Matchers.is(1)).body("rows[0].cells.'Impact hours'",org.hamcrest.Matchers.is(""));
        given().queryParam("basis","unresolved").get(endpoint).then().statusCode(200).body("total",org.hamcrest.Matchers.is(1));
        given().queryParam("basis","unsupported").get(endpoint).then().statusCode(400);
        Data exported=csv.read(runs.artifact(r.id,"updated_90-day.csv",null),false);assertEquals(3,exported.rows.size());assertEquals("216.75",exported.rows.getFirst().get("Impact hours"));assertEquals("999",exported.rows.getFirst().get("Original Impact hours"));
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(runs.zip(r.id)))){zip.getNextEntry();assertArrayEquals(runs.artifact(r.id,"updated_90-day.csv",null),zip.readAllBytes());}
        assertArrayEquals(original,Files.readAllBytes(snapshot));
    }
    @Test void currentExportRoundsOnlyReportingValuesAndMirrorsCurrentTotal() {
        Data source=new Data(List.of(VIEWS,"Current total","Duration (in hours)","Attendance hours"),List.of(new Row("one",Map.of(VIEWS,"12.5","Current total","12.5","Duration (in hours)","1.5","Attendance hours","12.5")),new Row("tiny",Map.of(VIEWS,"0.000001")),new Row("invalid",Map.of(VIEWS,"bad")),new Row("blank",Map.of(VIEWS,""))));
        Data report=Calculations.reported(source,true);assertEquals(List.of("13","1","bad",""),report.rows.stream().map(r->r.get(VIEWS)).toList());assertEquals("13",report.rows.getFirst().get("Current total"));assertEquals("1.5",report.rows.getFirst().get("Duration (in hours)"));assertEquals("12.5",report.rows.getFirst().get("Attendance hours"));assertEquals("12.5",source.rows.getFirst().get(VIEWS));
    }
    @Test void suppliedBlankUrlHistoryNeedsNoManualReviewBaselineOrDurationEvenOnRetry() throws Exception {
        Run r=run();r.workbook.put("missingBaselines",99);Data source=new Data(List.of(VIEWS,URL,"Current total","Trailing 90-day","Type of activity","Name of activity"),List.of(row("valid","166.5",""),row("zero","0",""),row("missing","",""),row("invalid","bad",""),row("negative","-1",""),row("linked","10","https://blog.example.com/post")));
        workspace.data(r.id,"trailing-source",source);r.measurements.put("blank:valid",new Measurement("9999","VIEWS","",r.createdAt,"Old cached value",false));workspace.save(r);byte[] original=Files.readAllBytes(workspace.runPath(r.id,"state/trailing-source.json"));pipeline.trailing(new PhaseTask(r.id,4));Run refreshed=workspace.load(r.id);
        assertTrue(refreshed.issues.stream().noneMatch(i->Set.of("valid","zero").contains(i.rowId())));assertTrue(refreshed.issues.stream().anyMatch(i->i.rowId().equals("missing")));assertTrue(refreshed.issues.stream().anyMatch(i->i.rowId().equals("invalid")));assertTrue(refreshed.issues.stream().anyMatch(i->i.rowId().equals("negative")));assertTrue(refreshed.issues.stream().anyMatch(i->i.rowId().equals("linked")&&i.metricType().equals("BASELINE")));
        Row retained=pipeline.stage(refreshed,4,"updated_90-day.csv").rows.getFirst();assertEquals("166.5",retained.get(VIEWS));assertEquals("",retained.get("Current total"));assertEquals("Supplied historical input — not a trailing delta",retained.get("Metric basis"));assertFalse(retained.get(NOTE).contains("manual collection"));assertArrayEquals(original,Files.readAllBytes(workspace.runPath(r.id,"state/trailing-source.json")));
    }
    @Test void httpResourcesAreUpgradedWithoutTheObsoleteHttpsOnlyWarning() throws Exception {
        assertEquals("https://dzone.com/articles/test?x=a%20b",MetricCollectors.resourceUri(" http://dzone.com/articles/test?x=a%20b ").toString());assertEquals("https://www.youtube.com/watch?v=x",MetricCollectors.resourceUri("https://www.youtube.com/watch?v=x").toString());
        for(String invalid:List.of("javascript:alert(1)","file:///tmp/input","https://user:secret@example.com/test","http://example.com:8080/test","not-a-url"))assertThrows(IOException.class,()->MetricCollectors.resourceUri(invalid));
    }
}
