package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.ibm.advocacy.csv.CsvCodec;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static com.ibm.advocacy.model.MetricsSchema.*;

@QuarkusTest @TestProfile(LocalDZoneCollectionTest.Enabled.class)
class LocalDZoneCollectionTest {
    public static class Enabled implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("metrics.dzone.local-collection-enabled","true");}}
    @Inject RunService runs;@Inject RuntimeWorkspace workspace;@Inject CsvCodec csv;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    Run await(String id) throws Exception {for(int n=0;n<400;n++){Run run=runs.get(id);if(run.status.equals("AWAITING_APPROVAL"))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timed out");}
    byte[] current(String second) {return ("Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Blog post / slide shares / .io updates,One,7,,https://dzone.com/articles/one\nB,Y,Blog post / slide shares / .io updates,Two,8,,"+second+"\n").getBytes(StandardCharsets.UTF_8);}
    Run review(String second) throws Exception {Run run=runs.create(current(second),null);runs.start(run.id,true);return await(run.id);}
    RunService.LocalDZoneValue value(String row,String count){return new RunService.LocalDZoneValue(row,count,"DZone article Views counter: "+count+" Views; exact public count; synthetic local Chrome collection");}
    @Test void oneConfirmedBatchRefreshesAllCountsWithoutChangingInputs() throws Exception {
        Run review=review("https://dzone.com/articles/two");byte[] original=Files.readAllBytes(workspace.runPath(review.id,"rawdata/AvocadoBot 2.csv"));
        given().contentType("application/json").body(Map.of("version",review.version,"confirmed",true,"values",List.of(value("c1","100"),value("c2","200")))).post("/api/runs/"+review.id+"/dzone/measurements").then().statusCode(202);
        Run updated=await(review.id);assertTrue(updated.issues.isEmpty());assertEquals(2,updated.measurements.size());
        assertEquals(1,updated.events.stream().filter(event->event.agent().equals("Local DZone collection")).count());
        assertArrayEquals(original,Files.readAllBytes(workspace.runPath(review.id,"rawdata/AvocadoBot 2.csv")));
        var rows=csv.read(runs.artifact(review.id,"digital.csv",null),false).rows;assertEquals("100",rows.get(0).get(VIEWS));assertEquals("200",rows.get(1).get(VIEWS));
    }
    @Test void validationIsAtomicForUnsupportedSourcesAndConflictingTotals() throws Exception {
        Run review=review("https://example.com/not-dzone");
        assertThrows(WebApplicationException.class,()->runs.measuredDZone(review.id,review.version,List.of(value("c1","100"),value("c2","200"))));
        assertTrue(runs.get(review.id).measurements.isEmpty());assertEquals(review.version,runs.get(review.id).version);
        assertThrows(WebApplicationException.class,()->runs.measuredDZone(review.id,review.version,List.of(value("c1","100"),value("c1","101"))));assertTrue(runs.get(review.id).measurements.isEmpty());
    }
    @Test void staleUnconfirmedFractionalAndUnknownRequestsDoNotSave() throws Exception {
        Run review=review("https://dzone.com/articles/two");String path="/api/runs/"+review.id+"/dzone/measurements";
        given().contentType("application/json").body(Map.of("version",review.version,"confirmed",false,"values",List.of(value("c1","100")))).post(path).then().statusCode(400);
        given().contentType("application/json").body(Map.of("version",review.version+1,"confirmed",true,"values",List.of(value("c1","100")))).post(path).then().statusCode(409);
        assertThrows(WebApplicationException.class,()->runs.measuredDZone(review.id,review.version,List.of(value("c1","1.5"))));
        assertThrows(WebApplicationException.class,()->runs.measuredDZone(review.id,review.version,List.of(value("unknown","100"))));assertTrue(runs.get(review.id).measurements.isEmpty());
    }
    @Test void rawTrailingTotalsUseExistingSharedAllocationAndPreserveBaselines() throws Exception {
        byte[] past=("Author,Tool,Type of activity,Name of activity,Current total,Views / attendance,Link / URL to resource\nA,X,Blog post / slide shares / .io updates,Shared,200,5,https://dzone.com/articles/shared\nB,Y,Blog post / slide shares / .io updates,Shared,200,5,https://dzone.com/articles/shared\n").getBytes(StandardCharsets.UTF_8);
        byte[] empty="Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Customer meeting,Meeting,10,1,\n".getBytes(StandardCharsets.UTF_8);
        Run run=runs.create(empty,past);runs.start(run.id,true);for(int n=0;n<3;n++){Run review=await(run.id);runs.approve(run.id,review.phase,review.version,RunService.digest(review),false);}Run review=await(run.id);
        runs.measuredDZone(run.id,review.version,List.of(value("t1","1000")));Run updated=await(run.id);assertEquals(1,updated.measurements.size());assertTrue(updated.issues.isEmpty());
        for(var row:csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false).rows){assertEquals("200",row.get("Previous total"));assertEquals("500",row.get("Current total"));assertEquals("300",row.get(VIEWS));}
        for(var row:workspace.data(run.id,"trailing-source").rows)assertEquals("200",row.get("Current total"));
    }
}
