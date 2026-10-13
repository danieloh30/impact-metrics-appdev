package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.resource.RunResource;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(DeleteRunTest.Profile.class)
class DeleteRunTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides(){return Map.of("metrics.runtime-root","target/delete-tests/test-runtimes");}
    }
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject RunResource resource;
    @TempDir Path outside;
    byte[] input(){return "Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Duration (in hours),Link / URL to resource\n2026-09-10,A,X,Shared fixture,Customer meeting,10,1,\n".getBytes(StandardCharsets.UTF_8);}
    @BeforeEach void prepare() throws Exception {
        runs.resetHistory(true);
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
    }
    Run awaitIdle(String id) throws Exception {
        for(int i=0;i<400;i++){Run run=runs.get(id);if(run.status.equals("AWAITING_APPROVAL"))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}
        throw new AssertionError("Worker did not finish");
    }
    @Test void confirmationRequiredAndOnlySelectedRunFilesAreDeleted() throws Exception {
        Run target=runs.create(input(),null),other=runs.create(input(),null);
        for(String name:List.of("rawdata/original.xlsx","output/7/current.csv","manual/measurement.json"))workspace.bytes(workspace.runPath(target.id,name),"synthetic-private-input".getBytes());
        byte[] retained=Files.readAllBytes(workspace.runPath(other.id,"state/run.json"));
        given().delete("/api/runs/"+target.id).then().statusCode(400);
        given().queryParam("confirm",false).delete("/api/runs/"+target.id).then().statusCode(400);
        assertEquals("READY",runs.get(target.id).status);
        given().queryParam("confirm",true).delete("/api/runs/"+target.id).then().statusCode(200).body("deletedRun",org.hamcrest.Matchers.equalTo(target.id));
        assertFalse(Files.exists(workspace.root().resolve("runs").resolve(target.id),LinkOption.NOFOLLOW_LINKS));
        given().get("/api/runs/"+target.id).then().statusCode(404);
        given().queryParam("confirm",true).delete("/api/runs/"+target.id).then().statusCode(404);
        assertThrows(NotFoundException.class,()->runs.retry(target.id));
        assertArrayEquals(retained,Files.readAllBytes(workspace.runPath(other.id,"state/run.json")));assertEquals(List.of(other.id),runs.list().stream().map(run->run.id).toList());
    }
    @Test void sharedSessionsChartCacheInstructionsAndLegacyInputsArePreserved() throws Exception {
        Run run=runs.create(input(),null);Map<String,byte[]> retained=new HashMap<>();
        for(String name:List.of("RUN.md","AGENTS.md",".sessions/sharepoint/auth.json",".sessions/linkedin/auth.json","chart-history/source.json","AvocadoBot 2.csv","trailing_90-day.csv")){
            if(!List.of("RUN.md","AGENTS.md").contains(name))workspace.bytes(workspace.path(name),("synthetic-shared-"+name).getBytes());retained.put(name,Files.readAllBytes(workspace.path(name)));
        }
        runs.deleteRun(run.id,true);
        for(var entry:retained.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(workspace.path(entry.getKey())));
    }
    @Test void nestedLinksAreUnlinkedWithoutFollowingExternalTargets() throws Exception {
        Run run=runs.create(input(),null);Path sentinel=outside.resolve("sentinel.txt");Files.writeString(sentinel,"retain external data");
        Path nested=workspace.runPath(run.id,"nested"),dangling=workspace.runPath(run.id,"dangling");Files.createSymbolicLink(nested,outside);Files.createSymbolicLink(dangling,outside.resolve("missing"));
        runs.deleteRun(run.id,true);assertEquals("retain external data",Files.readString(sentinel));assertFalse(Files.exists(nested,LinkOption.NOFOLLOW_LINKS));assertFalse(Files.exists(dangling,LinkOption.NOFOLLOW_LINKS));
    }
    @Test void symlinkedRunAndRunsRootAreRejectedWithoutDeletingTargets() throws Exception {
        Run run=runs.create(input(),null);Path runsRoot=workspace.path("runs"),directory=workspace.runPath(run.id,""),backup=outside.resolve("saved-run");Files.move(directory,backup);Files.createSymbolicLink(directory,backup);
        try{assertThrows(IllegalArgumentException.class,()->runs.deleteRun(run.id,true));assertTrue(Files.exists(backup.resolve("state/run.json")));}
        finally{Files.delete(directory);Files.move(backup,directory);}
        runs.deleteRun(run.id,true);Files.delete(runsRoot);Path sentinel=outside.resolve("sentinel.txt");Files.writeString(sentinel,"keep");Files.createSymbolicLink(runsRoot,outside);
        try{assertThrows(IllegalArgumentException.class,()->runs.deleteRun(run.id,true));assertEquals("keep",Files.readString(sentinel));}
        finally{Files.delete(runsRoot);Files.createDirectories(runsRoot);}
    }
    @Test void processingTargetCannotBeDeletedButAnotherIdleRunCan() throws Exception {
        Run processing=runs.create(input(),null),idle=runs.create(input(),null);
        synchronized(runs.lock(processing.id)){
            runs.start(processing.id,true);
            ClientErrorException failure=assertThrows(ClientErrorException.class,()->runs.deleteRun(processing.id,true));assertEquals(409,failure.getResponse().getStatus());
            assertTrue(Files.exists(workspace.runPath(processing.id,"state/run.json")));
            runs.deleteRun(idle.id,true);assertThrows(NotFoundException.class,()->runs.get(idle.id));assertEquals("RUNNING_PHASE",runs.get(processing.id).status);
        }
        awaitIdle(processing.id);runs.deleteRun(processing.id,true);
    }
    @Test void publicationTargetIsProtectedUntilPublicationReleasesOwnership() throws Exception {
        Run run=runs.create(input(),null);runs.publicationStarted(run);
        try{
            ClientErrorException failure=assertThrows(ClientErrorException.class,()->runs.deleteRun(run.id,true));assertEquals(409,failure.getResponse().getStatus());
            assertEquals("PUBLISHING_WORKBOOK",workspace.load(run.id).status);assertTrue(Files.exists(workspace.runPath(run.id,"state/run.json")));
        }finally{runs.publicationFailed(run.id,false,"Synthetic publication ended");}
        runs.deleteRun(run.id,true);assertThrows(NotFoundException.class,()->runs.get(run.id));
    }
    @Test void malformedIdsAndTraversalCannotReachOutsideWorkspace() throws Exception {
        Path sentinel=outside.resolve("sentinel.txt");Files.writeString(sentinel,"keep");
        given().queryParam("confirm",true).delete("/api/runs/not-a-run").then().statusCode(400);
        for(String id:List.of("../sentinel",outside.toString(),"a".repeat(37)))assertThrows(IllegalArgumentException.class,()->runs.deleteRun(id,true));
        given().queryParam("confirm",true).delete("/api/runs/"+UUID.randomUUID()).then().statusCode(404);assertEquals("keep",Files.readString(sentinel));
    }
    @Test void individualDeletionTerminatesExistingStreamWithResetWithoutError() throws Exception {
        Run run=runs.create(input(),null);var items=new CopyOnWriteArrayList<Map<String,Object>>();var first=new CountDownLatch(1);var completed=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        var subscription=resource.events(run.id,0).subscribe().with(item->{items.add(item);first.countDown();},error->{failure.set(error);completed.countDown();},completed::countDown);
        try{assertTrue(first.await(5,TimeUnit.SECONDS));runs.deleteRun(run.id,true);assertTrue(completed.await(5,TimeUnit.SECONDS));assertNull(failure.get());assertEquals("RESET",items.getLast().get("status"));assertEquals(run.id,items.getLast().get("runId"));}
        finally{subscription.cancel();}
    }
}
