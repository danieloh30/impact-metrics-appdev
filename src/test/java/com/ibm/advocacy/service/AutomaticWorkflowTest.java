package com.ibm.advocacy.service;

import com.ibm.advocacy.collector.SharePointCollector;
import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static com.ibm.advocacy.model.MetricsSchema.*;

@QuarkusTest
class AutomaticWorkflowTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject WorkbookPublicationService publications;
    @Inject SharePointCollector collector;

    @BeforeEach void instructions() throws Exception {
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
        FakeSharePointCollector.files.clear();FakeSharePointCollector.denyWrites=false;
    }
    @AfterEach void fixtures(){FakeSharePointCollector.files.clear();FakeSharePointCollector.denyWrites=false;}
    byte[] current(String count,String duration){return ("Date,Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\n2026-09-10,A;B,X;Y,Customer meeting,Shared meeting,"+count+","+duration+",\n").getBytes(StandardCharsets.UTF_8);}
    Run await(String id,String status) throws Exception {
        for(int i=0;i<500;i++){Run run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}
        throw new AssertionError("Timed out waiting for "+status+": "+runs.get(id).status);
    }

    @Test void cleanSevenPhaseWorkflowAdvancesAndPersistsAutomaticAuditWithoutHumanValues() throws Exception {
        byte[] history="Date,Author,Tool,Type of activity,Name of activity,Views / attendance,Current total,Previous total,Trailing 90-day,Link / URL to resource\n2026-08-10,A,X,Recorded video,Retained historical evidence,10,40,0,Yes,\n".getBytes(StandardCharsets.UTF_8);
        Run run=runs.create(current("20","2"),history);runs.start(run.id,true,true);Run done=await(run.id,"COMPLETED");
        assertEquals(7,done.phase);assertTrue(done.automaticProcessing);assertTrue(done.contractAccepted);assertTrue(done.issues.isEmpty());
        assertEquals(List.of(1,2,3,4),done.approvals.stream().map(Approval::phase).toList());assertTrue(done.approvals.stream().allMatch(a->a.automatic()&&!a.acknowledgedIssues()&&a.digest().matches("[0-9a-f]{64}")));
        assertEquals(4,done.events.stream().filter(e->e.agent().equals("Automatic progression")).count());
        assertTrue(done.measurements.isEmpty());assertTrue(done.monthEndValues.isEmpty());
        assertEquals(4,runs.artifactData(run.id,"current.csv",null).rows.size());assertTrue(runs.artifactData(run.id,"current.csv",null).rows.stream().allMatch(row->row.get(VIEWS).equals("10")));
        Run stored=workspace.load(run.id);assertTrue(stored.automaticProcessing);assertEquals(done.approvals,stored.approvals);
        assertEquals(Set.of("digital.csv","in-person.csv","follow-up.csv","updated_90-day.csv","current.csv"),done.artifacts.keySet());
    }

    @Test void blockingAttendancePausesAndRealMeasurementResumesAutomaticProcessing() throws Exception {
        Run run=runs.create(current("invalid","2"),null);byte[] original=Files.readAllBytes(workspace.runPath(run.id,"state/source.json"));runs.start(run.id,true,true);
        Run paused=await(run.id,"AWAITING_APPROVAL");assertEquals(2,paused.phase);assertEquals(1,paused.issues.size());assertTrue(paused.issues.getFirst().blocking());assertEquals("ATTENDANCE",paused.issues.getFirst().metricType());
        assertEquals(List.of(1),paused.approvals.stream().map(Approval::phase).toList());assertFalse(Files.exists(workspace.runPath(run.id,"state/phase-3-in-person.json")));assertTrue(paused.measurements.isEmpty());
        assertThrows(BadRequestException.class,()->runs.approve(run.id,2,paused.version,RunService.digest(paused),true,true));
        runs.measured(run.id,paused.version,"c1","12","ATTENDANCE","Verified attendance from fixture");Run done=await(run.id,"COMPLETED");
        assertTrue(done.issues.isEmpty());assertEquals(List.of(1,2,3),done.approvals.stream().map(Approval::phase).toList());assertTrue(done.approvals.stream().allMatch(Approval::automatic));
        assertEquals(1,done.measurements.size());assertTrue(done.measurements.get("attendance:c1").manual());assertTrue(done.monthEndValues.isEmpty());
        assertTrue(runs.artifactData(run.id,"current.csv",null).rows.stream().allMatch(row->row.get(VIEWS).equals("6")));assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"state/source.json")));
    }

    @Test void nonblockingCollectionNeedsExplicitAcknowledgmentAndNeverInventsEvidence() throws Exception {
        String input="Author,Tool,Type of activity,Name of activity,Link / URL to resource\nA,X,Recorded video,Unsupported resource,https://manual.example.test/unsupported\n";
        Run run=runs.create(input.getBytes(StandardCharsets.UTF_8),null);runs.start(run.id,true,true);Run paused=await(run.id,"AWAITING_APPROVAL");
        assertEquals(1,paused.phase);assertEquals(1,paused.issues.size());assertFalse(paused.issues.getFirst().blocking());assertTrue(paused.approvals.isEmpty());assertTrue(paused.measurements.isEmpty());
        assertThrows(BadRequestException.class,()->runs.approve(run.id,1,paused.version,RunService.digest(paused),false,true));
        runs.approve(run.id,1,paused.version,RunService.digest(paused),true,true);Run done=await(run.id,"COMPLETED");
        Approval acknowledged=done.approvals.getFirst();assertEquals(1,acknowledged.phase());assertTrue(acknowledged.acknowledgedIssues());assertFalse(acknowledged.automatic());assertTrue(done.approvals.stream().skip(1).allMatch(Approval::automatic));
        assertTrue(done.measurements.isEmpty());assertTrue(done.monthEndValues.isEmpty());assertEquals(1,done.artifacts.get("follow-up.csv").rows());assertEquals("",runs.artifactData(run.id,"current.csv",null).rows.getFirst().get(VIEWS));
    }

    @Test void manualLegacyStartCanOptIntoAutomaticAdvancementButStaleApprovalCannot() throws Exception {
        Run run=runs.create(current("20","1"),null);runs.start(run.id,true);Run ready=await(run.id,"AWAITING_APPROVAL");assertFalse(ready.automaticProcessing);assertTrue(ready.approvals.isEmpty());
        String path="/api/runs/"+run.id+"/phases/1/approve";
        given().contentType("application/json").body(Map.of("version",ready.version,"digest","stale","acknowledgeIssues",false,"automaticProcessing",true)).post(path).then().statusCode(409);
        assertFalse(runs.get(run.id).automaticProcessing);assertTrue(runs.get(run.id).approvals.isEmpty());
        given().contentType("application/json").body(Map.of("version",ready.version,"digest",RunService.digest(ready),"acknowledgeIssues",false,"automaticProcessing",true)).post(path).then().statusCode(202);
        Run done=await(run.id,"COMPLETED");assertTrue(done.automaticProcessing);assertTrue(done.approvals.stream().allMatch(Approval::automatic));
        int approved=done.approvals.size();given().contentType("application/json").body(Map.of("version",ready.version,"digest",RunService.digest(ready),"automaticProcessing",true)).post(path).then().statusCode(409);assertEquals(approved,runs.get(run.id).approvals.size());
    }

    @Test void startApiAcceptsAutomaticOptionOnceAndRequiresContractAcceptance() throws Exception {
        Run run=runs.create(current("20","1"),null);String path="/api/runs/"+run.id+"/start";
        given().contentType("application/json").body(Map.of("acceptContract",false,"automaticProcessing",true)).post(path).then().statusCode(400);assertEquals("READY",runs.get(run.id).status);assertFalse(runs.get(run.id).automaticProcessing);
        given().contentType("application/json").body(Map.of("acceptContract",true,"automaticProcessing",true)).post(path).then().statusCode(202).body("automaticProcessing",equalTo(true));
        given().contentType("application/json").body(Map.of("acceptContract",true,"automaticProcessing",true)).post(path).then().statusCode(409);
        Run done=await(run.id,"COMPLETED");assertEquals(List.of(1,2,3),done.approvals.stream().map(Approval::phase).toList());assertTrue(done.approvals.stream().allMatch(Approval::automatic));
    }

    @Test void persistedAutomaticOptionSurvivesInterruptedRunRecovery() throws Exception {
        Run run=runs.create(current("20","1"),null);run.automaticProcessing=true;run.contractAccepted=true;run.phase=1;run.status="RUNNING_PHASE";workspace.save(run);
        assertTrue(workspace.load(run.id).automaticProcessing);assertEquals("FAILED_RETRYABLE",runs.get(run.id).status);runs.retry(run.id);Run done=await(run.id,"COMPLETED");assertTrue(done.automaticProcessing);assertTrue(done.approvals.stream().allMatch(Approval::automatic));assertTrue(done.events.stream().anyMatch(e->e.agent().equals("Collection retry")));
    }

    @Test void publicationRemainsHumanReviewedAndAutomaticProcessingResumesAfterVerifiedSave() throws Exception {
        WorkbookPublicationTest fixture=new WorkbookPublicationTest();String url="https://ibm.sharepoint.com/sites/DEPT-DeveloperRelations/Shared%20Documents/Automatic%20fixture.xlsx";byte[] history=fixture.history();FakeSharePointCollector.files.put(collector.fileUri(url).toASCIIString(),history);int writes=FakeSharePointCollector.writes.get();
        Run run=runs.createSharePointWorkbooks(fixture.current(false),"AvocadoBot fixture.xlsx",history,"Metrics fixture.xlsx","2026-09","Current month inputs",List.of("August inputs"),"https://ibm-my.sharepoint.com/personal/test/Documents/AvocadoBot.xlsx",url);byte[] original=Files.readAllBytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"));
        runs.start(run.id,true,true);Run checkpoint=await(run.id,"AWAITING_PUBLICATION");assertEquals(3,checkpoint.phase);assertTrue(checkpoint.automaticProcessing);assertEquals(List.of(1,2,3),checkpoint.approvals.stream().map(Approval::phase).toList());assertTrue(checkpoint.approvals.stream().allMatch(Approval::automatic));assertEquals(writes,FakeSharePointCollector.writes.get());assertFalse(checkpoint.artifacts.containsKey("updated_90-day.csv"));assertTrue(checkpoint.monthEndValues.isEmpty());
        var preview=publications.preview(run.id,checkpoint.version,checkpoint.reportingRevision);assertThrows(BadRequestException.class,()->publications.publish(run.id,checkpoint.version,checkpoint.reportingRevision,preview.publication().planDigest,false));assertEquals(writes,FakeSharePointCollector.writes.get());
        publications.publish(run.id,checkpoint.version,checkpoint.reportingRevision,preview.publication().planDigest,true);Run done=await(run.id,"COMPLETED");assertEquals(List.of(1,2,3,4),done.approvals.stream().map(Approval::phase).toList());assertTrue(done.approvals.stream().allMatch(Approval::automatic));assertEquals(writes+1,FakeSharePointCollector.writes.get());assertEquals("PUBLISHED",done.publication.status);assertEquals("CURRENT_INPUTS",done.publication.stage);assertTrue(done.monthEndValues.isEmpty());assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx")));assertEquals("100",runs.report(run.id,"combined").impact());
    }
}
