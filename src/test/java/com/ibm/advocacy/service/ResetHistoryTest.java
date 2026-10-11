package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.resource.RunResource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ResetHistoryTest.Profile.class)
class ResetHistoryTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("metrics.runtime-root","target/reset-tests/test-runtimes");
        }
    }
    @Inject RunResource resource;
    @Inject RunService service;
    @Inject RuntimeWorkspace workspace;
    @TempDir Path outside;
    byte[] input() {
        return "Author,Tool,Type of activity,Link / URL to resource\nA,X,Recorded video,https://example.com/video\n".getBytes(StandardCharsets.UTF_8);
    }
    @BeforeEach void prepare() throws Exception {
        service.resetHistory(true);
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
    }
    @Test void resetRequiresConfirmationAndClearsHistoryAndLegacyInputs() throws Exception {
        workspace.bytes(workspace.path("AvocadoBot 2.csv"),input());
        workspace.bytes(workspace.path("trailing_90-day.csv"),"seed trailing bytes".getBytes());
        workspace.bytes(workspace.path(".sessions/linkedin/session"),"session bytes".getBytes());
        workspace.bytes(workspace.path(".sessions/sharepoint/session"),"sharepoint session bytes".getBytes());
        workspace.bytes(workspace.path("unrelated.csv"),"keep unrelated data".getBytes());
        Map<String,byte[]> preserved=new java.util.HashMap<>();
        for(String name:List.of("RUN.md","AGENTS.md",".sessions/linkedin/session",".sessions/sharepoint/session","unrelated.csv"))preserved.put(name,Files.readAllBytes(workspace.path(name)));
        Run run=service.create(input(),null);
        workspace.bytes(workspace.runPath(run.id,"output/1/current.csv"),"generated output".getBytes());
        given().contentType("application/json").body("{}").post("/api/runs/reset").then().statusCode(400);
        given().contentType("application/json").post("/api/runs/reset").then().statusCode(400);
        assertEquals("READY",service.get(run.id).status);
        assertArrayEquals(input(),Files.readAllBytes(workspace.path("AvocadoBot 2.csv")));
        assertEquals("seed trailing bytes",Files.readString(workspace.path("trailing_90-day.csv")));
        int deleted=given().contentType("application/json").body("{\"confirm\":true}").post("/api/runs/reset").then().statusCode(200).extract().path("deletedRuns");
        assertEquals(1,deleted);assertEquals(List.of(),service.list());
        assertFalse(Files.exists(workspace.path("AvocadoBot 2.csv")));
        assertFalse(Files.exists(workspace.path("trailing_90-day.csv")));
        try(var entries=Files.list(workspace.path("runs"))) { assertEquals(0,entries.count()); }
        given().get("/api/runs/"+run.id).then().statusCode(404);
        for(var entry:preserved.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(workspace.path(entry.getKey())));
        assertEquals(0,service.resetHistory(true));
        assertEquals("READY",service.create(input(),null).status);
    }
    @Test void resetRemovesLegacyInputsWithoutSavedRunsAndIsRepeatable() throws Exception {
        Files.delete(workspace.path("runs"));
        for(String name:List.of("AvocadoBot 2.csv","trailing_90-day.csv"))workspace.bytes(workspace.path(name),input());
        assertEquals(0,service.resetHistory(true));
        assertTrue(Files.isDirectory(workspace.path("runs")));
        for(String name:List.of("AvocadoBot 2.csv","trailing_90-day.csv"))assertFalse(Files.exists(workspace.path(name)));
        assertEquals(0,service.resetHistory(true));
    }
    @Test void resetUnlinksLegacyInputSymlinksWithoutDeletingExternalData() throws Exception {
        for(String name:List.of("AvocadoBot 2.csv","trailing_90-day.csv")) {
            Path external=outside.resolve(name);Files.writeString(external,"keep source data");
            Files.createSymbolicLink(workspace.root().resolve(name),external);
        }
        assertEquals(0,service.resetHistory(true));
        for(String name:List.of("AvocadoBot 2.csv","trailing_90-day.csv")) {
            assertEquals("keep source data",Files.readString(outside.resolve(name)));
            assertFalse(Files.exists(workspace.root().resolve(name),java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }
    }
    @Test void unexpectedLegacyDirectoryIsRejectedBeforeDeletingHistory() throws Exception {
        Run run=service.create(input(),null);
        Path legacy=workspace.path("trailing_90-day.csv"),nested=legacy.resolve("keep.txt");
        Files.createDirectory(legacy);Files.writeString(nested,"keep");
        workspace.bytes(workspace.path("AvocadoBot 2.csv"),input());
        try {
            assertThrows(IllegalArgumentException.class,()->service.resetHistory(true));
            assertEquals("keep",Files.readString(nested));
            assertEquals("READY",service.get(run.id).status);
            assertArrayEquals(input(),Files.readAllBytes(workspace.path("AvocadoBot 2.csv")));
        } finally {Files.delete(nested);Files.delete(legacy);}
    }
    @Test void liveEventsEmitResetAndCompleteWhenTheRunIsDeleted() throws Exception {
        Run run=service.create(input(),null);
        var items=new CopyOnWriteArrayList<Map<String,Object>>();
        var first=new CountDownLatch(1);var completed=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        var subscription=resource.events(run.id,0).subscribe().with(
            item->{items.add(item);first.countDown();},
            error->{failure.set(error);completed.countDown();},completed::countDown);
        try {
            assertTrue(first.await(5,TimeUnit.SECONDS),"Live stream should emit its first state");
            assertEquals("READY",items.getFirst().get("status"));
            assertEquals(1,service.resetHistory(true));
            assertTrue(completed.await(5,TimeUnit.SECONDS),"Deleted run stream should complete");
            assertNull(failure.get(),"Expected deletion must not fail the SSE stream");
            assertEquals("RESET",items.getLast().get("status"));
            assertEquals(run.id,items.getLast().get("runId"));
        } finally { subscription.cancel(); }
    }
    @Test void resetCannotDeleteAnActiveWorker() throws Exception {
        Run run=service.create(input(),null);
        workspace.bytes(workspace.path("AvocadoBot 2.csv"),input());
        workspace.bytes(workspace.path("trailing_90-day.csv"),"legacy trailing".getBytes());
        // Hold the existing per-run lock so the real worker cannot finish its
        // final state transition before the reset attempt.
        synchronized(service.lock(run.id)) {
            service.start(run.id,true);
            ClientErrorException error=assertThrows(ClientErrorException.class,()->service.resetHistory(true));
            assertEquals(409,error.getResponse().getStatus());
            assertTrue(Files.exists(workspace.runPath(run.id,"state/run.json")));
            assertArrayEquals(input(),Files.readAllBytes(workspace.path("AvocadoBot 2.csv")));
            assertEquals("legacy trailing",Files.readString(workspace.path("trailing_90-day.csv")));
        }
        for(int attempt=0;attempt<200;attempt++) {
            if(service.get(run.id).status.equals("AWAITING_APPROVAL")) {
                try { assertEquals(1,service.resetHistory(true));return; }
                catch(ClientErrorException workerFinishing) { /* Active ID removed immediately after the final write. */ }
            }
            Thread.sleep(25);
        }
        fail("Worker did not become idle for reset");
    }
    @Test void resetUnlinksNestedSymlinksWithoutDeletingTheirTargets() throws Exception {
        Path sentinel=outside.resolve("keep.txt");Files.writeString(sentinel,"keep");
        Run run=service.create(input(),null);
        Path link=workspace.runPath(run.id,"external");Files.createSymbolicLink(link,outside);
        assertEquals(1,service.resetHistory(true));
        assertEquals("keep",Files.readString(sentinel));assertFalse(Files.exists(link,java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }
    @Test void resetRejectsASymlinkAtTheRunsRoot() throws Exception {
        Path runs=workspace.path("runs");Files.delete(runs);Files.createSymbolicLink(runs,outside);
        Path sentinel=outside.resolve("keep.txt");Files.writeString(sentinel,"keep");
        try {
            assertThrows(IllegalArgumentException.class,()->service.resetHistory(true));
            assertEquals("keep",Files.readString(sentinel));
        } finally { Files.delete(runs);Files.createDirectories(runs); }
    }
}
