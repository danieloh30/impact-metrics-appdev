package com.ibm.advocacy.service;

import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.storage.RuntimeWorkspace;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class RunWorkflowTest {
    @Inject RunService service;
    @Inject RuntimeWorkspace workspace;
    @Inject CsvCodec csv;
    @BeforeEach void instructions() throws Exception { for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name))); }
    byte[] input() { return ("Author of activity,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\r\n"+
        "A;B,X;Y,Recorded video,Digital,,,https://www.linkedin.com/posts/test\r\n"+
        "A;B,X;Y,Customer meeting,Meeting,25,2,\r\n"+
        "A;B,X,Analyst or press briefing,Other,,,\r\n").getBytes(StandardCharsets.UTF_8); }
    Run await(String id,String state) throws Exception {
        for(int attempt=0;attempt<200;attempt++) { Run run=service.get(id);if(run.status.equals(state)) { Thread.sleep(25);return service.get(id); }if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(25); }
        fail("Timed out waiting for "+state+": "+service.get(id).status);return null;
    }
    void approve(Run run) throws Exception { service.approve(run.id,run.phase,run.version,RunService.digest(run),true); }
    @Test void fullCurrentWorkflowUsesAgentsApprovalsAndExactExport() throws Exception {
        Run run=service.create(input(),null);assertEquals(3,run.currentRows);assertEquals("READY",run.status);
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->service.start(run.id,false));service.start(run.id,true);Run phase1=await(run.id,"AWAITING_APPROVAL");
        assertEquals(1,phase1.phase);assertEquals(1,phase1.issues.size());assertEquals(0,phase1.artifacts.get("digital.csv").rows());assertEquals(1,phase1.artifacts.get("follow-up.csv").rows());
        assertThrows(jakarta.ws.rs.ClientErrorException.class,()->service.approve(run.id,1,phase1.version,"stale",true));
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->service.measured(run.id,phase1.version,"c1","10","VIDEO_VIEWS","wrong metric"));
        service.measured(run.id,phase1.version,"c1","100","REACTIONS","Verified post total_reactions=100, comments=3, reposts=2");Run measured=await(run.id,"AWAITING_APPROVAL");
        assertTrue(measured.version>phase1.version);assertEquals(1,measured.artifacts.get("digital.csv").rows());assertEquals(0,measured.artifacts.get("follow-up.csv").rows());
        approve(measured);Run phase2=await(run.id,"AWAITING_APPROVAL");assertEquals(2,phase2.phase);approve(phase2);
        Run phase3=await(run.id,"AWAITING_APPROVAL");assertEquals(3,phase3.phase);assertEquals(4,phase3.artifacts.get("digital.csv").rows());assertEquals(4,phase3.artifacts.get("in-person.csv").rows());approve(phase3);
        Run complete=await(run.id,"COMPLETED");assertEquals(7,complete.phase);assertEquals(3,complete.approvals.size());
        Data finalData=csv.read(service.artifact(run.id,"current.csv",null),false);assertEquals(FINAL_HEADERS,finalData.headers);assertEquals(10,finalData.rows.size());
        assertTrue(finalData.rows.stream().allMatch(r->r.get("Current total").equals(r.get(VIEWS))));
        assertEquals(4,finalData.rows.stream().filter(r->r.get("Name of activity").equals("Digital")&&r.get(VIEWS).equals("25")).count());
        assertEquals(4,finalData.rows.stream().filter(r->r.get("Name of activity").equals("Meeting")&&r.get(VIEWS).equals("13")).count());
        assertTrue(complete.events.stream().anyMatch(e->e.agent().equals("AttributionAgent")));assertTrue(complete.events.stream().anyMatch(e->e.agent().equals("InstructionAgent")));
        assertArrayEquals(input(),Files.readAllBytes(workspace.runPath(run.id,"rawdata/AvocadoBot 2.csv")));
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(service.zip(run.id)))) { Set<String> names=new HashSet<>();for(ZipEntry e;(e=zip.getNextEntry())!=null;)names.add(e.getName());assertEquals(Set.of("digital.csv","in-person.csv","follow-up.csv","current.csv"),names); }
    }
    @Test void trailingSharedMeasurementIsAllocatedOnceAndEventMetricChecked() throws Exception {
        byte[] past=("Author,Tool,Type of activity,Name of activity,Current total,Previous total,Views / attendance,Link / URL to resource\r\n"+
            "A,X,Livestream,café,40,0,0,https://www.linkedin.com/events/123/\r\n"+
            "B,X,Livestream,café,40,0,0,https://www.linkedin.com/events/123/\r\n").getBytes(Charset.forName("windows-1252"));
        Run run=service.create(input(),past);service.start(run.id,true);approve(await(run.id,"AWAITING_APPROVAL"));approve(await(run.id,"AWAITING_APPROVAL"));approve(await(run.id,"AWAITING_APPROVAL"));
        Run phase4=await(run.id,"AWAITING_APPROVAL");assertEquals(4,phase4.phase);assertEquals(1,phase4.issues.size());
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->service.measured(run.id,phase4.version,"t1","120","REACTIONS","wrong"));
        service.measured(run.id,phase4.version,"t1","120","VIDEO_VIEWS","Post analytics / Video performance / Video Views");Run measured=await(run.id,"AWAITING_APPROVAL");
        Data data=csv.read(service.artifact(run.id,"updated_90-day.csv",null),false);assertEquals(2,data.rows.size());assertTrue(data.rows.stream().allMatch(r->r.get("Current total").equals("60")&&r.get("Previous total").equals("40")&&r.get(VIEWS).equals("20")));assertEquals("café",data.rows.getFirst().get("Name of activity"));
        approve(measured);assertEquals("COMPLETED",await(run.id,"COMPLETED").status);
    }
    @Test void independentBlankUrlMeasurementsAndNumericCorrection() throws Exception {
        byte[] data=("Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Recorded video,First,,,\nA,X,Recorded video,Second,,,\nA,X,Customer meeting,Meeting,bad,1,\n").getBytes();
        Run run=service.create(data,null);service.start(run.id,true);Run phase1=await(run.id,"AWAITING_APPROVAL");assertEquals(2,phase1.issues.size());
        service.measured(run.id,phase1.version,"c2","0","VIEWS","Verified zero");Run measured=await(run.id,"AWAITING_APPROVAL");assertEquals(1,measured.artifacts.get("digital.csv").rows());assertEquals(1,measured.issues.size());approve(measured);
        Run phase2=await(run.id,"AWAITING_APPROVAL");assertTrue(phase2.issues.getFirst().blocking());assertThrows(jakarta.ws.rs.BadRequestException.class,()->service.approve(run.id,2,phase2.version,RunService.digest(phase2),true));
        service.measured(run.id,phase2.version,"c3","12","ATTENDANCE","Verified attendance");approve(await(run.id,"AWAITING_APPROVAL"));approve(await(run.id,"AWAITING_APPROVAL"));await(run.id,"COMPLETED");
    }
    @Test void recoveryDetectsInterruptedPhaseAndRetriesFromSources() throws Exception {
        Run run=service.create(input(),null);run.phase=1;run.status="RUNNING_PHASE";workspace.save(run);assertEquals("FAILED_RETRYABLE",service.get(run.id).status);service.retry(run.id);assertEquals(1,await(run.id,"AWAITING_APPROVAL").phase);
    }
    @Test void runtimeBoundaryRejectsTraversalAndSymlink() throws Exception {
        assertThrows(IllegalArgumentException.class,()->workspace.path("../escape.csv"));
        Path link=workspace.path("escape-"+UUID.randomUUID());Files.createSymbolicLink(link,Path.of("/private/tmp"));assertThrows(IllegalArgumentException.class,()->workspace.path(link.getFileName()+"/file.csv"));Files.delete(link);
        assertThrows(IllegalArgumentException.class,()->service.get("../../devops"));
    }
    @Test void instructionsAreFrozenAndLaterEditsFailTheRun() throws Exception {
        Run run=service.create(input(),null);workspace.bytes(workspace.runPath(run.id,"instructions/RUN.md"),"changed".getBytes());service.start(run.id,true);
        for(int i=0;i<200;i++){Run changed=service.get(run.id);if(changed.status.equals("FAILED_RETRYABLE")){assertTrue(changed.error.contains("Frozen instructions"));return;}Thread.sleep(25);}fail("Instruction mutation was not rejected");
    }
    @Test void runsAreIsolatedAndApiValidatesUploads() throws Exception {
        Run one=service.create(input(),null),two=service.create(input(),null);assertNotEquals(one.id,two.id);service.start(one.id,true);await(one.id,"AWAITING_APPROVAL");assertEquals("READY",service.get(two.id).status);
        given().get("/api/config").then().statusCode(200);
        given().multiPart("current","broken.csv","not,a,valid,csv\n".getBytes()).post("/api/runs").then().statusCode(405);
        given().get("/api/runs/"+one.id+"/artifacts/secret.txt/download").then().statusCode(400);
        given().get("/api/runs/"+two.id+"/artifacts/current.csv/download").then().statusCode(404);
    }
    @Test void durationAndTrailingBaselineCanBeCorrectedWithoutChangingRawData() throws Exception {
        byte[] current="Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Customer meeting,Meeting,10,invalid,\n".getBytes();
        byte[] past="Author,Tool,Type of activity,Name of activity,Current total,Link / URL to resource\nA,X,Recorded video,Video,bad,https://example.com/video\n".getBytes();
        Run run=service.create(current,past);service.start(run.id,true);approve(await(run.id,"AWAITING_APPROVAL"));
        Run attendance=await(run.id,"AWAITING_APPROVAL");assertEquals("DURATION",attendance.issues.getFirst().metricType());
        service.measured(run.id,attendance.version,"c1","2","DURATION","Verified two-hour duration");approve(await(run.id,"AWAITING_APPROVAL"));approve(await(run.id,"AWAITING_APPROVAL"));
        Run trailing=await(run.id,"AWAITING_APPROVAL");service.measured(run.id,trailing.version,"t1","100","VIEWS","Verified total");Run invalid=await(run.id,"AWAITING_APPROVAL");assertEquals("BASELINE",invalid.issues.getFirst().metricType());
        service.measured(run.id,invalid.version,"t1","80","BASELINE","Corrected previous total from source evidence");Run corrected=await(run.id,"AWAITING_APPROVAL");assertTrue(corrected.issues.isEmpty());
        Data updated=csv.read(service.artifact(run.id,"updated_90-day.csv",null),false);assertEquals("20",updated.rows.getFirst().get(VIEWS));assertEquals("80",updated.rows.getFirst().get("Previous total"));
        assertArrayEquals(current,Files.readAllBytes(workspace.runPath(run.id,"rawdata/AvocadoBot 2.csv")));assertArrayEquals(past,Files.readAllBytes(workspace.runPath(run.id,"rawdata/trailing_90-day.csv")));
        approve(corrected);await(run.id,"COMPLETED");
    }
    @Test void modifiedRootInstructionsRequireAReviewedContract() throws Exception {
        byte[] original=Files.readAllBytes(workspace.path("RUN.md"));try { workspace.bytes(workspace.path("RUN.md"),(new String(original,StandardCharsets.UTF_8)+"\nNew rule\n").getBytes());assertThrows(jakarta.ws.rs.BadRequestException.class,()->service.create(input(),null)); }finally { workspace.bytes(workspace.path("RUN.md"),original); }
    }
    @Test void concurrentAgentScopesRemainIsolated() throws Exception {
        Run one=service.create(input(),null),two=service.create(input(),null);service.start(one.id,true);service.start(two.id,true);
        Run first=await(one.id,"AWAITING_APPROVAL"),second=await(two.id,"AWAITING_APPROVAL");assertEquals(1,first.artifacts.get("follow-up.csv").rows());assertEquals(1,second.artifacts.get("follow-up.csv").rows());assertEquals(first.id,one.id);assertEquals(second.id,two.id);
    }
}
