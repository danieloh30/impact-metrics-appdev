package com.ibm.advocacy.service;

import com.ibm.advocacy.resource.RunResource;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.ibm.advocacy.model.Event;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RunEventsBackpressureTest {
    @Inject RunService runs;
    @Inject RunResource resource;
    @Inject RuntimeWorkspace workspace;
    com.ibm.advocacy.model.Run fixture() throws Exception {
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
        return runs.create("Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Link / URL to resource\n2026-09-10,Daniel Oh,IBM Bob,Workshop,Customer workshop,10,\n".getBytes(StandardCharsets.UTF_8),null);
    }
    @Test void pausedClientSurvivesMissedTicksThenReceivesPersistedEventsAndReset() throws Exception {
        var run=fixture();
        var subscriber=AssertSubscriber.<Map<String,Object>>create(0);
        resource.events(run.id,0).subscribe().withSubscriber(subscriber);
        try {
            // Hold demand through multiple real one-second timer ticks. Before the
            // overflow policy this terminated with BackPressureFailure.
            new CountDownLatch(1).await(2200,TimeUnit.MILLISECONDS);
            subscriber.assertNotTerminated();assertNull(subscriber.getFailure());
            run=runs.get(run.id);run.events.add(new Event(2,Instant.now().toString(),"Fixture","Event created while client paused"));workspace.save(run);
            subscriber.request(1);subscriber.awaitItems(1,Duration.ofSeconds(5));
            subscriber.request(1);subscriber.awaitItems(2,Duration.ofSeconds(5));
            assertTrue(((List<?>)subscriber.getItems().getLast().get("events")).stream().anyMatch(item->((Event)item).sequence()==2),"Skipped refresh ticks must not discard stored events");
            Files.delete(workspace.runPath(run.id,"state/run.json"));
            subscriber.request(10);subscriber.awaitCompletion(Duration.ofSeconds(5));
            subscriber.assertCompleted();assertNull(subscriber.getFailure());assertEquals("RESET",subscriber.getItems().getLast().get("status"));
        } finally {subscriber.cancel();}
    }
    @Test void slowRunReadSkipsTicksAndResumesWithoutFailing() throws Exception {
        var run=fixture();var subscriber=AssertSubscriber.<Map<String,Object>>create(Long.MAX_VALUE);
        resource.events(run.id,0).subscribe().withSubscriber(subscriber);
        try {
            subscriber.awaitItems(1,Duration.ofSeconds(5));
            // Long synchronized run operations can delay the worker's next read,
            // even when the SSE client itself keeps requesting updates.
            synchronized(io.quarkus.arc.ClientProxy.unwrap(runs)) {
                new CountDownLatch(1).await(2200,TimeUnit.MILLISECONDS);
                subscriber.assertNotTerminated();assertNull(subscriber.getFailure());
            }
            subscriber.awaitItems(2,Duration.ofSeconds(5));subscriber.assertNotTerminated();assertNull(subscriber.getFailure());
        } finally {subscriber.cancel();}
    }
}
