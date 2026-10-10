package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static com.ibm.advocacy.model.MetricsSchema.*;

@QuarkusTest
class TrailingInputReviewTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject CsvCodec csv;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    Run await(String id,String status) throws Exception {for(int n=0;n<300;n++){Run run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timed out");}
    void approve(Run run) throws Exception {runs.approve(run.id,run.phase,run.version,RunService.digest(run),false);}
    Run phase4(String baseline,String views,String duration,String url) throws Exception {
        byte[] current="Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Customer meeting,Current,10,1,\n".getBytes(StandardCharsets.UTF_8);
        byte[] history=("Author,Tool,Type of activity,Name of activity,Current total,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Meetup (user group),AAIF & IBM Open-Source AI Meetup,"+baseline+","+views+","+duration+","+url+"\n").getBytes(StandardCharsets.UTF_8);
        Run run=runs.create(current,history);runs.start(run.id,true);for(int phase=1;phase<=3;phase++)approve(await(run.id,"AWAITING_APPROVAL"));return await(run.id,"AWAITING_APPROVAL");
    }
    @Test void contextLoadsOriginalValuesEvenForPreviouslyPersistedReviewIssues() throws Exception {
        Run run=phase4("90","30","2","");
        given().queryParam("rowId","t1").get("/api/runs/"+run.id+"/trailing-input").then().statusCode(200).body("originalViews",org.hamcrest.Matchers.is("30")).body("originalDuration",org.hamcrest.Matchers.is("2")).body("baseline",org.hamcrest.Matchers.is("90")).body("canRetain",org.hamcrest.Matchers.is(true));
        Data output=csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false);assertEquals("30",output.rows.getFirst().get("Original Views / attendance"));assertEquals("2",output.rows.getFirst().get("Original Duration (in hours)"));assertEquals("30",output.rows.getFirst().get(VIEWS));
    }
    @Test void keepOriginalRecordsDecisionAndCanCompleteWithoutFreshTotalOrZeroDelta() throws Exception {
        Run run=phase4("90","30","2","");
        given().contentType("application/json").body(Map.of("version",run.version,"rowId","t1","keepOriginal",true,"views","999","duration","99")).post("/api/runs/"+run.id+"/trailing-inputs").then().statusCode(202);
        Run reviewed=await(run.id,"AWAITING_APPROVAL");assertTrue(reviewed.issues.isEmpty());assertTrue(reviewed.version>run.version);assertFalse(workspace.load(run.id).inputReviews.get("t1").edited());
        Data data=csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false);Row row=data.rows.getFirst();assertEquals("30",row.get(VIEWS));assertEquals("2",row.get("Duration (in hours)"));assertEquals("90",row.get("Current total"));assertEquals("90",row.get("Previous total"));assertTrue(row.get("Metric basis").startsWith("Retained input"));assertTrue(row.get(NOTE).contains("no fresh lifetime total or trailing delta"));
        assertThrows(ClientErrorException.class,()->runs.reviewTrailingInput(run.id,run.version,"t1","30","2",true,""));approve(reviewed);assertEquals("COMPLETED",await(run.id,"COMPLETED").status);
        assertEquals("30",workspace.data(run.id,"trailing-source").rows.getFirst().get(VIEWS));
        Data original=workspace.data(run.id,"trailing-source");original.rows.getFirst().cells.put(VIEWS,"1,000");workspace.data(run.id,"trailing-source",original);Run loaded=workspace.load(run.id);loaded.inputReviews.clear();workspace.save(loaded);assertEquals("1000",runs.trailingInput(run.id,"t1").views());assertEquals("1,000",runs.trailingInput(run.id,"t1").originalViews());
    }
    @Test void editedInputsKeepOriginalEvidenceAndValidateNumbersAndResourceScope() throws Exception {
        Run run=phase4("90","30","2","");
        assertThrows(BadRequestException.class,()->runs.reviewTrailingInput(run.id,run.version,"t1","-1","2",false,""));assertThrows(BadRequestException.class,()->runs.reviewTrailingInput(run.id,run.version,"t1","35","bad",false,""));
        runs.reviewTrailingInput(run.id,run.version,"t1","35","1.5",false,"Reviewed attendance record");Run reviewed=await(run.id,"AWAITING_APPROVAL");assertTrue(reviewed.issues.isEmpty());
        Row result=csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false).rows.getFirst();assertEquals("35",result.get(VIEWS));assertEquals("1.5",result.get("Duration (in hours)"));assertEquals("30",result.get("Original Views / attendance"));assertEquals("2",result.get("Original Duration (in hours)"));assertTrue(result.get("Metric basis").startsWith("Reviewed input"));assertEquals("90",result.get("Current total"));
        var context=runs.trailingInput(run.id,"t1");assertEquals("30",context.originalViews());assertEquals("35",context.views());
        Run linked=phase4("90","30","2","https://example.com/event");assertThrows(BadRequestException.class,()->runs.reviewTrailingInput(linked.id,linked.version,"t1","30","2",true,""));
    }
    @Test void retainWithoutBaselineDoesNotInventTotalsAndMissingInputNeedsEditing() throws Exception {
        Run noBaseline=phase4("","30","2","");assertTrue(noBaseline.issues.isEmpty());runs.reviewTrailingInput(noBaseline.id,noBaseline.version,"t1",null,null,true,"");Run reviewed=await(noBaseline.id,"AWAITING_APPROVAL");assertTrue(reviewed.issues.isEmpty());Row data=csv.read(runs.artifact(noBaseline.id,"updated_90-day.csv",null),false).rows.getFirst();assertEquals("",data.get("Current total"));assertEquals("",data.get("Previous total"));assertEquals("30",data.get(VIEWS));
        Run missing=phase4("90","","2","");assertFalse(runs.trailingInput(missing.id,"t1").canRetain());assertThrows(BadRequestException.class,()->runs.reviewTrailingInput(missing.id,missing.version,"t1",null,null,true,""));runs.reviewTrailingInput(missing.id,missing.version,"t1","0","2",false,"Verified zero attendance");assertTrue(await(missing.id,"AWAITING_APPROVAL").issues.isEmpty());
    }@Test void historicalReviewOmitsAbsentDurationAndCanKeepOrEditViews() throws Exception {
    Run run=phase4("78","78","","");Data source=workspace.data(run.id,"trailing-source");source.headers.remove("Duration (in hours)");source.rows.getFirst().cells.remove("Duration (in hours)");workspace.data(run.id,"trailing-source",source);
    var context=runs.trailingInput(run.id,"t1");assertFalse(context.hasDuration());assertTrue(context.canRetain());
    runs.reviewTrailingInput(run.id,run.version,"t1",null,null,true,"");Run kept=await(run.id,"AWAITING_APPROVAL");assertTrue(kept.issues.isEmpty());assertEquals("",kept.inputReviews.get("t1").duration());
    runs.reviewTrailingInput(run.id,kept.version,"t1","90",null,false,"");Run edited=await(run.id,"AWAITING_APPROVAL");assertTrue(edited.issues.isEmpty());assertEquals("90",edited.inputReviews.get("t1").views());assertEquals("",edited.inputReviews.get("t1").duration());assertEquals("",csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false).rows.getFirst().get("Duration (in hours)"));
}
@Test void historicalMeasuredSharesDefaultToBaselineAndAreNotDividedTwice() throws Exception {
    Run run=phase4("1966.5","0","","http://dzone.com/articles/architecting-autonomous-network-ecosystems");Data source=workspace.data(run.id,"trailing-source");Row second=source.rows.getFirst().copy();second.id="t2";source.rows.add(second);workspace.data(run.id,"trailing-source",source);
    assertEquals("1966.5",runs.measurementInput(run.id,"t1","VIEWS").value());assertTrue(runs.measurementInput(run.id,"t1","VIEWS").attributed());
    given().contentType("application/json").body(Map.of("version",run.version,"rowId","t1","value","1966.5","type","VIEWS","attributed",true)).post("/api/runs/"+run.id+"/measurements").then().statusCode(202);
    Run confirmed=await(run.id,"AWAITING_APPROVAL");assertTrue(confirmed.issues.isEmpty());assertEquals("3933",confirmed.measurements.values().stream().filter(m->m.type().equals("VIEWS")).findFirst().orElseThrow().value());
    Data result=csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false);assertEquals(2,result.rows.size());assertTrue(result.rows.stream().allMatch(row->row.get("Current total").equals("1966.5")&&row.get(VIEWS).equals("0")));assertEquals("1966.5",runs.measurementInput(run.id,"t1","VIEWS").value());
    runs.measured(run.id,confirmed.version,"t1","2000","VIEWS","",true);Run edited=await(run.id,"AWAITING_APPROVAL");assertEquals("2000",runs.measurementInput(run.id,"t2","VIEWS").value());assertTrue(csv.read(runs.artifact(run.id,"updated_90-day.csv",null),false).rows.stream().allMatch(row->row.get(VIEWS).equals("34")));
}

}
