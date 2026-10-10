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

@QuarkusTest
class DZonePipelineTest {
    @Inject Pipeline pipeline;
    @Inject RuntimeWorkspace workspace;
    final String url="https://dzone.com/articles/agents-md-java";
    Row row(String id,String tool){return new Row(id,Map.of("Author","Daniel Oh","Tool",tool,"Type of activity","Blog post / slide shares / .io updates","Name of activity","AGENTS.md Makes Your Java Codebase AI-Agent Ready",URL,url,VIEWS,"0","Current total","1653.5","Trailing 90-day","Yes"));}
    Run stored(int phase,List<Row> rows) throws Exception {
        Run run=new Run();run.id=UUID.randomUUID().toString();run.createdAt=Instant.now().toString();run.phase=phase;run.status="AWAITING_APPROVAL";run.trailing=phase==4;workspace.save(run);
        workspace.data(run.id,phase==4?"trailing-source":"source",new Data(new ArrayList<>(rows.getFirst().cells.keySet()),rows));return run;
    }
    @Test void automaticDZoneTotalIsAllocatedOnceAndClearsManualReview() throws Exception {
        Run run=stored(4,List.of(row("one","IBM Runtimes"),row("two","IBM Bob")));
        byte[] original=Files.readAllBytes(workspace.runPath(run.id,"state/trailing-source.json"));AtomicInteger calls=new AtomicInteger();Pipeline actual=ClientProxy.unwrap(pipeline);MetricCollectors saved=actual.collectors;
        try {
            actual.collectors=new MetricCollectors(){@Override public Measurement collect(String resource){assertEquals(url,resource);calls.incrementAndGet();return new Measurement("4900","VIEWS",resource,Instant.now().toString(),"DZone 4.9K Views; approximate public display",false);}};
            pipeline.trailing(new PhaseTask(run.id,4));Run result=workspace.load(run.id);assertTrue(result.issues.isEmpty());assertEquals(1,calls.get());
            Data output=pipeline.stage(result,4,"updated_90-day.csv");assertEquals(List.of("796.5","796.5"),output.rows.stream().map(r->r.get(VIEWS)).toList());assertTrue(output.rows.stream().allMatch(r->r.get("Current total").equals("2450")&&r.get("Previous total").equals("1653.5")&&r.get(NOTE).contains("approximate")));
            assertEquals(List.of("797","797"),Calculations.reported(output,false).rows.stream().map(r->r.get(VIEWS)).toList());assertFalse(result.measurements.get(Calculations.urlKey(url)).manual());
            pipeline.trailing(new PhaseTask(run.id,4));assertEquals(1,calls.get());assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"state/trailing-source.json")));
        }finally {actual.collectors=saved;}
    }
    @Test void dzoneFailureRemainsActionableAndNeverUsesThePreviousBaselineAsFreshViews() throws Exception {
        Run run=stored(4,List.of(row("one","IBM Runtimes")));Pipeline actual=ClientProxy.unwrap(pipeline);MetricCollectors saved=actual.collectors;
        try {
            actual.collectors=new MetricCollectors(){@Override public Measurement collect(String resource)throws Exception{throw new IOException("DZone blocked or unavailable (HTTP 403); retry collection or enter views manually");}};
            pipeline.trailing(new PhaseTask(run.id,4));Run result=workspace.load(run.id);assertEquals(1,result.issues.size());assertTrue(result.issues.getFirst().reason().contains("HTTP 403"));assertTrue(result.measurements.isEmpty());
            Row output=pipeline.stage(result,4,"updated_90-day.csv").rows.getFirst();assertEquals("",output.get(VIEWS));assertEquals("1653.5",output.get("Previous total"));
        }finally {actual.collectors=saved;}
    }
    @Test void currentDigitalDZoneViewsGoToDigitalResultsWithoutFollowUp() throws Exception {
        Run run=stored(1,List.of(row("one","IBM Runtimes")));Pipeline actual=ClientProxy.unwrap(pipeline);MetricCollectors saved=actual.collectors;
        try {
            actual.collectors=new MetricCollectors(){@Override public Measurement collect(String resource){return new Measurement("4900","VIEWS",resource,Instant.now().toString(),"DZone approximate public display",false);}};
            pipeline.digital(new PhaseTask(run.id,1));Run result=workspace.load(run.id);assertTrue(result.issues.isEmpty());assertEquals("4900",pipeline.stage(result,1,"digital.csv").rows.getFirst().get(VIEWS));assertEquals(0,pipeline.stage(result,1,"follow-up.csv").rows.size());
        }finally {actual.collectors=saved;}
    }
}
