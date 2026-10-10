package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.ibm.advocacy.csv.CsvCodec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static com.ibm.advocacy.model.MetricsSchema.*;

@QuarkusTest
class MeasurementInputTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject CsvCodec csv;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    Run await(String id) throws Exception {for(int n=0;n<300;n++){Run run=runs.get(id);if(run.status.equals("AWAITING_APPROVAL"))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timed out");}
    byte[] input(String type,String views,String duration,String url) {return ("Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\nA,Quarkus,"+type+",Part 3: Tracing and Observability,"+views+","+duration+","+url+"\n").getBytes(StandardCharsets.UTF_8);}
    @Test void urlBlogPrefillsThousandViewsAndOmittedNoteIsAcceptedWithoutChangingOriginal() throws Exception {
        Run run=runs.create(input("Blog post / slide shares / .io updates","1000","","https://blog.example.com/post"),null);runs.start(run.id,true);Run review=await(run.id);
        given().queryParam("rowId","c1").queryParam("type","VIEWS").get("/api/runs/"+run.id+"/measurement-input").then().statusCode(200).body("value",org.hamcrest.Matchers.is("1000")).body("originalViews",org.hamcrest.Matchers.is("1000"));
        given().contentType("application/json").body(Map.of("version",review.version,"rowId","c1","value","1200","type","VIEWS")).post("/api/runs/"+run.id+"/measurements").then().statusCode(202);
        Run updated=await(run.id);assertTrue(updated.issues.isEmpty());assertEquals("1200",runs.measurementInput(run.id,"c1","VIEWS").value());assertEquals("1000",runs.measurementInput(run.id,"c1","VIEWS").originalViews());assertTrue(updated.measurements.values().iterator().next().evidence().contains("not provided"));assertEquals("1200",csv.read(runs.artifact(run.id,"digital.csv",null),false).rows.getFirst().get(VIEWS));
    }
    @Test void trailingUrlContextShowsOriginalViewsAndBaselineAndBlankNoteKeepsDeltaSemantics() throws Exception {
        byte[] history="Author,Tool,Type of activity,Name of activity,Current total,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Recorded video,Historical post,800,1000,2,https://blog.example.com/post\n".getBytes(StandardCharsets.UTF_8);
        Run run=runs.create(input("Customer meeting","10","1",""),history);runs.start(run.id,true);
        for(int n=0;n<3;n++){Run review=await(run.id);runs.approve(run.id,review.phase,review.version,RunService.digest(review),false);}Run phase4=await(run.id);
        assertEquals("800",runs.measurementInput(run.id,"t1","VIEWS").value());assertEquals("800",runs.measurementInput(run.id,"t1","BASELINE").value());assertEquals("2",runs.measurementInput(run.id,"t1","DURATION").value());
        runs.measured(run.id,phase4.version,"t1","1200","VIEWS","");assertTrue(await(run.id).issues.isEmpty());Row row=csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false).rows.getFirst();assertEquals("400",row.get(VIEWS));assertEquals("1000",row.get("Original Views / attendance"));assertEquals("Trailing delta",row.get("Metric basis"));
    }
    @Test void optionalNotesDoNotRelaxNumericTypeLengthOrRowValidation() throws Exception {
        Run run=runs.create(input("Recorded video","0","","https://blog.example.com/post"),null);runs.start(run.id,true);Run review=await(run.id);
        assertEquals("0",runs.measurementInput(run.id,"c1","VIEWS").value());assertThrows(BadRequestException.class,()->runs.measurementInput(run.id,"missing","VIEWS"));assertThrows(BadRequestException.class,()->runs.measurementInput(run.id,"c1","unknown"));assertThrows(BadRequestException.class,()->runs.measured(run.id,review.version,"c1","1","VIDEO_VIEWS",null));assertThrows(BadRequestException.class,()->runs.measured(run.id,review.version,"c1","1","VIEWS","x".repeat(1001)));
        given().contentType("application/json").body(Map.of("version",review.version,"rowId","c1","value","-1","type","VIEWS")).post("/api/runs/"+run.id+"/measurements").then().statusCode(400);
    }
}
