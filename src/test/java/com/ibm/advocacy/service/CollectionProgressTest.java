package com.ibm.advocacy.service;
import com.ibm.advocacy.collector.MetricCollectors;
import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CollectionProgressTest {
 @Inject Pipeline pipeline;
 @Inject RuntimeWorkspace workspace;
 @Inject RunService runs;
 final String first="https://dzone.com/articles/first",blocked="https://dzone.com/articles/blocked",cached="https://github.com/example/repo";
 Row row(String id,String url){return new Row(id,Map.of("Author","Daniel Oh","Tool","IBM Runtimes","Type of activity","Recorded video","Name of activity",id,URL,url,VIEWS,"20","Duration (in hours)","2","Current total","100","Trailing 90-day","Yes"));}
 Run fixture(int phase,List<Row> rows) throws Exception {
  Run run=new Run();run.id=UUID.randomUUID().toString();run.createdAt=Instant.now().toString();run.phase=phase;run.status="AWAITING_APPROVAL";run.trailing=phase==4;workspace.save(run);
  workspace.data(run.id,phase==4?"trailing-source":"source",new Data(new ArrayList<>(rows.getFirst().cells.keySet()),rows));return run;
 }
 Measurement measurement(String url){return new Measurement("3700","VIEWS",url,Instant.now().toString(),"Synthetic public counter",false);}
 @Test void progressIsSavedBeforeRequestsCountsUniqueResourcesAndRetryReusesSuccessfulChecks() throws Exception {
  Run r=fixture(1,List.of(row("one",first),row("duplicate",first),row("blocked",blocked),row("cached",cached),row("excluded","https://www.the-main-thread.com/p/example"),row("supplied","")));
  r.measurements.put(Calculations.urlKey(cached),measurement(cached));workspace.save(r);byte[] source=Files.readAllBytes(workspace.runPath(r.id,"state/source.json"));
  Pipeline actual=ClientProxy.unwrap(pipeline);MetricCollectors saved=actual.collectors;AtomicInteger checks=new AtomicInteger(),closed=new AtomicInteger();
  try {
   actual.collectors=new MetricCollectors(){
    @Override public AutoCloseable batch(){return closed::incrementAndGet;}
    @Override public Measurement collect(String url)throws Exception {
     checks.incrementAndGet();Run state=workspace.load(r.id);assertEquals("CHECKING",state.collectionProgress.state);assertEquals(3,state.collectionProgress.total);assertEquals(url,state.collectionProgress.resource);
     given().get("/api/runs/"+r.id).then().statusCode(200).body("run.collectionProgress.resource",is(url));
     if(url.equals(blocked))throw new IOException("DZone background collection blocked (HTTP 403); retry or enter verified views manually");return measurement(url);
    }
   };
   pipeline.digital(new PhaseTask(r.id,1));CollectionProgress p=workspace.load(r.id).collectionProgress;
   assertEquals(3,p.total);assertEquals(3,p.completed);assertEquals(1,p.collected);assertEquals(1,p.cached);assertEquals(1,p.failed);assertEquals("COMPLETE",p.state);assertTrue(p.lastError.contains("HTTP 403"));assertTrue(p.resource.isEmpty());assertNotNull(Instant.parse(p.finishedAt));assertEquals(2,checks.get());assertEquals(1,closed.get());
   pipeline.digital(new PhaseTask(r.id,1));p=workspace.load(r.id).collectionProgress;assertEquals(0,p.collected);assertEquals(2,p.cached);assertEquals(1,p.failed);assertEquals(3,checks.get());assertEquals(2,closed.get());assertArrayEquals(source,Files.readAllBytes(workspace.runPath(r.id,"state/source.json")));
  }finally {actual.collectors=saved;}
 }
 @Test void historicalSharesCountOneResourceAndKeepExactBaselineAllocation() throws Exception {
  Run r=fixture(4,List.of(row("one",first),row("two",first),row("excluded","https://eldermoraes.com/post"),row("supplied","")));
  Pipeline actual=ClientProxy.unwrap(pipeline);MetricCollectors saved=actual.collectors;
  try {
   actual.collectors=new MetricCollectors(){@Override public Measurement collect(String url){return measurement(url);}};
   pipeline.trailing(new PhaseTask(r.id,4));Run done=workspace.load(r.id);assertEquals(1,done.collectionProgress.total);assertEquals(1,done.collectionProgress.collected);assertEquals(0,done.collectionProgress.failed);
   List<Row> output=pipeline.stage(done,4,"updated_90-day.csv").rows;assertEquals("1750",output.getFirst().get(VIEWS));assertEquals("100",output.getFirst().get("Previous total"));assertEquals("1850",output.getFirst().get("Current total"));
   pipeline.trailing(new PhaseTask(r.id,4));assertEquals(1,workspace.load(r.id).collectionProgress.cached);
  }finally {actual.collectors=saved;}
 }
 @Test void cancellationClosesBatchAndLeavesProgressStoppedRatherThanComplete() throws Exception {
  Run r=fixture(1,List.of(row("one",first),row("second",blocked)));Pipeline actual=ClientProxy.unwrap(pipeline);MetricCollectors saved=actual.collectors;AtomicInteger closed=new AtomicInteger();
  try {
   actual.collectors=new MetricCollectors(){@Override public AutoCloseable batch(){return closed::incrementAndGet;}@Override public Measurement collect(String url)throws Exception{runs.cancel(r.id);return measurement(url);}};
   assertThrows(java.util.concurrent.CancellationException.class,()->pipeline.digital(new PhaseTask(r.id,1)));CollectionProgress p=workspace.load(r.id).collectionProgress;assertEquals("STOPPED",p.state);assertEquals(1,p.completed);assertEquals(2,p.total);assertEquals(1,closed.get());
  }finally {actual.collectors=saved;}
 }
}
