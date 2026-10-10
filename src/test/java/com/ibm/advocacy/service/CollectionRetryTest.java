package com.ibm.advocacy.service;
import com.ibm.advocacy.collector.MetricCollectors;
import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CollectionRetryTest {
 @Inject RunService service;
 @Inject RuntimeWorkspace workspace;
 @Inject Pipeline pipeline;
 Run await(String id,int after) throws Exception {
  for(int i=0;i<300;i++) {
   Run run=service.get(id);
   if(run.status.equals("AWAITING_APPROVAL")&&run.version>after){Thread.sleep(25);return service.get(id);}
   if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);
   Thread.sleep(25);
  }
  throw new AssertionError("Retry did not finish");
 }
 @Test void retryEndpointRechecksFailedSharedResourceKeepsSuccessfulAndManualValuesAndPublishesReview() throws Exception {
  for(String name:new String[]{"RUN.md","AGENTS.md"})workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
  String good="https://dzone.com/articles/good",failed="https://dzone.com/articles/failed",manual="https://dzone.com/articles/manual";
  String input="Author,Tool,Type of activity,Name of activity,Link / URL to resource\nA,X,Recorded video,Good,"+good+"\nA,X,Recorded video,Failed,"+failed+"\nB,X,Recorded video,Shared,"+failed+"\nA,X,Recorded video,Manual,"+manual+"\n";
  Run run=service.create(input.getBytes(java.nio.charset.StandardCharsets.UTF_8),null);
  run.measurements.put(Calculations.urlKey(manual),new Measurement("77","VIEWS",manual,Instant.now().toString(),"User verified",true));workspace.save(run);
  Pipeline actual=ClientProxy.unwrap(pipeline);MetricCollectors saved=actual.collectors;
  var checks=new ConcurrentHashMap<String,Integer>();AtomicBoolean blocked=new AtomicBoolean(true);
  try {
   actual.collectors=new MetricCollectors(){@Override public Measurement collect(String url)throws Exception {
    checks.merge(url,1,Integer::sum);
    if(url.equals(failed)&&blocked.get())throw new IOException("DZone background collection blocked (HTTP 403)");
    return new Measurement("3700","VIEWS",url,Instant.now().toString(),"Synthetic public counter",false);
   }};
   service.start(run.id,true);Run first=await(run.id,0);assertEquals(2,first.issues.size());assertEquals(1,checks.get(good));assertEquals(1,checks.get(failed));assertFalse(checks.containsKey(manual));
   byte[] immutable=Files.readAllBytes(workspace.runPath(run.id,"state/source.json"));
   // A still-blocked retry publishes a new version and its actual error rather than silently succeeding.
   given().post("/api/runs/"+run.id+"/retry").then().statusCode(202).body("status",is("RUNNING_PHASE"));
   Run second=await(run.id,first.version);assertTrue(second.version>first.version);assertEquals(1,second.collectionProgress.failed);assertEquals(2,checks.get(failed));assertEquals(1,checks.get(good));
   blocked.set(false);given().post("/api/runs/"+run.id+"/retry").then().statusCode(202);
   Run done=await(run.id,second.version);assertTrue(done.issues.isEmpty());assertEquals(0,done.artifacts.get("follow-up.csv").rows());assertEquals(4,done.artifacts.get("digital.csv").rows());assertEquals(1,done.collectionProgress.collected);assertEquals(2,done.collectionProgress.cached);assertEquals(0,done.collectionProgress.failed);assertEquals(3,checks.get(failed));assertEquals(1,checks.get(good));assertEquals("77",done.measurements.get(Calculations.urlKey(manual)).value());assertArrayEquals(immutable,Files.readAllBytes(workspace.runPath(run.id,"state/source.json")));assertTrue(done.events.stream().anyMatch(event->event.agent().equals("Collection retry")));assertTrue(done.approvals.isEmpty());
  }finally {actual.collectors=saved;}
 }
}
