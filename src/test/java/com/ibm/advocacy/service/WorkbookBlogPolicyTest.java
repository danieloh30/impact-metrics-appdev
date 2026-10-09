package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkbookBlogPolicyTest {
    @Inject Pipeline pipeline;
    @Inject com.ibm.advocacy.collector.ManualCheckExclusions exclusions;
    @Inject RuntimeWorkspace workspace;
    Row blog(String id,String url,String views) { return new Row(id,Map.of(URL,url,VIEWS,views,"Current total","800","Type of activity","Blog post / slide shares / .io updates","Name of activity",id)); }
    Run run(List<Row> rows,boolean history) throws Exception {
        Run run=new Run();run.id=UUID.randomUUID().toString();run.createdAt=Instant.now().toString();run.trailing=history;
        workspace.save(run);workspace.data(run.id,history?"trailing-source":"source",new Data(new ArrayList<>(rows.getFirst().cells.keySet()),rows));return run;
    }
    @Test void exactBlogHostsUseSuppliedViewsIncludingZeroWithoutDurationOrManualCollection() throws Exception {
        List<Row> rows=List.of(blog("a","https://eldermoraes.com/post","1000"),blog("b","https://www.the-main-thread.com/p/post","0"),blog("c","https://www.eldermoraes.com/post","20"),blog("d","https://THE-MAIN-THREAD.COM/p/post","30"));
        Run run=run(rows,false);pipeline.digital(new PhaseTask(run.id,1));Run saved=workspace.load(run.id);
        assertTrue(saved.issues.isEmpty());assertTrue(saved.measurements.isEmpty());
        Data digital=pipeline.stage(saved,1,"digital.csv");assertEquals(List.of("1000","0","20","30"),digital.rows.stream().map(r->r.get(VIEWS)).toList());assertTrue(digital.rows.stream().allMatch(r->r.get(NOTE).contains("excluded from manual collection")));assertTrue(pipeline.stage(saved,1,"follow-up.csv").rows.isEmpty());
    }
    @Test void unrelatedAndLookalikeHostsKeepManualCollectionRules() throws Exception {
        Run run=run(List.of(blog("a","https://eldermoraes.com.evil.example/post","1000"),blog("b","https://blog.example.com/post","1000")),false);
        pipeline.digital(new PhaseTask(run.id,1));Run saved=workspace.load(run.id);assertEquals(2,saved.issues.size());assertTrue(saved.issues.stream().noneMatch(Issue::blocking));assertTrue(pipeline.stage(saved,1,"digital.csv").rows.isEmpty());
    }
    @Test void missingInvalidAndNegativeBlogViewsRequireCorrectionRatherThanCollection() throws Exception {
        Run run=run(List.of(blog("a","https://eldermoraes.com/one",""),blog("b","https://www.the-main-thread.com/two","bad"),blog("c","https://eldermoraes.com/three","-1"),blog("d","https://eldermoraes.com/one","10")),false);
        pipeline.digital(new PhaseTask(run.id,1));Run saved=workspace.load(run.id);assertEquals(1,pipeline.stage(saved,1,"digital.csv").rows.size());assertEquals(3,saved.issues.size());assertTrue(saved.issues.stream().allMatch(i->i.blocking()&&i.metricType().equals("VIEWS")));assertTrue(saved.issues.stream().noneMatch(i->i.reason().contains("manual collection")));
    }
    @Test void historicalBlogsPreserveWorkbookValuesWithoutClaimingFreshTotalsOrDeltas() throws Exception {
        Run run=run(List.of(blog("a","https://eldermoraes.com/post","1000"),blog("b","https://www.the-main-thread.com/post","0")),true);
        pipeline.trailing(new PhaseTask(run.id,4));Run saved=workspace.load(run.id);assertTrue(saved.issues.isEmpty());assertTrue(saved.measurements.isEmpty());
        Data data=pipeline.stage(saved,4,"updated_90-day.csv");assertEquals(List.of("1000","0"),data.rows.stream().map(r->r.get(VIEWS)).toList());
        for(Row row:data.rows){assertEquals("800",row.get("Current total"));assertEquals("800",row.get("Previous total"));assertEquals("Workbook content input — not a trailing delta",row.get("Metric basis"));assertTrue(row.get(NOTE).contains("no fresh lifetime total"));}
    }
    @Test void historicalInvalidViewsRequireCorrectionWithoutBaselineForRetainedInput() throws Exception {
        Row row=blog("a","https://eldermoraes.com/post","bad");row.cells.put("Current total","bad");Run run=run(List.of(row),true);
        pipeline.trailing(new PhaseTask(run.id,4));Run saved=workspace.load(run.id);assertEquals(Set.of("VIEWS"),new HashSet<>(saved.issues.stream().map(Issue::metricType).toList()));assertTrue(saved.issues.stream().allMatch(Issue::blocking));
    }
List<String> newSources() {
    return List.of("https://ibm.admin.webcasts.com/event","https://www.sigs.de/article","https://www.heise.de/developer/article","https://www.infoq.com/articles/test","https://quarkus.io/blog/test","https://www.youtube.com/watch?v=OSsatFo8Pmc","https://www.kevindubois.com/post","https://thedevconf.com/event","https://ec.yourlearning.ibm.com/activity");
}
@Test void allNewDigitalSourcesUseWorkbookMetricsWithoutCollection() throws Exception {
    List<Row> rows=new ArrayList<>();for(String url:newSources()){Row row=blog("c"+rows.size(),url,"1000");row.cells.put("Type of activity","Recorded video");rows.add(row);}
    Run run=run(rows,false);pipeline.digital(new PhaseTask(run.id,1));Run saved=workspace.load(run.id);
    assertTrue(saved.issues.isEmpty());assertTrue(saved.measurements.isEmpty());assertEquals(9,pipeline.stage(saved,1,"digital.csv").rows.size());assertTrue(pipeline.stage(saved,1,"follow-up.csv").rows.isEmpty());
    assertTrue(pipeline.stage(saved,1,"digital.csv").rows.stream().allMatch(row->row.get(VIEWS).equals("1000")));
}
@Test void allNewHistoricalSourcesRetainInputsWithoutInventingDeltas() throws Exception {
    List<Row> rows=new ArrayList<>();for(String url:newSources())rows.add(blog("t"+rows.size(),url,"1000"));
    Run run=run(rows,true);pipeline.trailing(new PhaseTask(run.id,4));Run saved=workspace.load(run.id);
    assertTrue(saved.issues.isEmpty());assertTrue(saved.measurements.isEmpty());
    Data output=pipeline.stage(saved,4,"updated_90-day.csv");assertEquals(9,output.rows.size());
    for(Row row:output.rows){assertEquals("1000",row.get(VIEWS));assertEquals("800",row.get("Current total"));assertEquals("800",row.get("Previous total"));assertEquals("Workbook content input — not a trailing delta",row.get("Metric basis"));}
}
@Test void exactHostAndBlogPathRulesDoNotExcludeLookalikesOrOtherQuarkusContent() {
    for(String url:List.of("https://SIGS.DE/post","https://www.eldermoraes.com/post","https://quarkus.io/blog","https://www.quarkus.io/blog/","https://quarkus.io/blog/2026/test?utm_source=test#section"))assertTrue(exclusions.matches(url),url);
    for(String url:List.of("https://www.infoq.com.evil.example/post","https://blog.infoq.com/post","https://ibm.admin.webcasts.com.evil.example/post","https://quarkus.io/blogger","https://quarkus.io/guides/blog","https://quarkus.io/","https://user@www.heise.de/post","https://www.heise.de:8443/post","not a URL"))assertFalse(exclusions.matches(url),url);
}
@Test void specificVideoAllowsTrackingButNotOtherVideosOrConflictingVideoIds() {
    for(String url:List.of("https://www.youtube.com/watch?v=OSsatFo8Pmc","https://youtube.com/watch?utm_source=share&v=OSsatFo8Pmc&t=10#section"))assertTrue(exclusions.matches(url),url);
    for(String url:List.of("https://www.youtube.com/watch?v=AnotherVideo","https://www.youtube.com/watch?v=ossatFo8Pmc","https://www.youtube.com/watch?v=OSsatFo8Pmc&v=AnotherVideo","https://www.youtube.com/watch/other?v=OSsatFo8Pmc","https://www.youtube.com/channel/OSsatFo8Pmc","https://youtu.be/OSsatFo8Pmc"))assertFalse(exclusions.matches(url),url);
}
@Test void nonExcludedVideoStillRequiresNormalCollectionRatherThanUsingInput() throws Exception {
    Run run=run(List.of(blog("a","https://www.youtube.com/watch?v=AnotherVideo","1000")),false);
    pipeline.digital(new PhaseTask(run.id,1));Run saved=workspace.load(run.id);assertEquals(1,saved.issues.size());assertFalse(saved.issues.getFirst().blocking());assertTrue(pipeline.stage(saved,1,"digital.csv").rows.isEmpty());
}
@Test void configuredListCanBeReplacedWithoutChangingPipelineCode() {
    var policy=new com.ibm.advocacy.collector.ManualCheckExclusions(List.of("example.com/articles","https://videos.example.com/watch?v=abc"));
    assertTrue(policy.matches("https://www.example.com/articles/new"));assertFalse(policy.matches("https://example.com/other"));assertFalse(policy.matches("https://eldermoraes.com/post"));
    assertTrue(policy.matches("https://videos.example.com/watch?utm=share&v=abc"));assertFalse(policy.matches("https://videos.example.com/watch?v=def"));
    assertFalse(new com.ibm.advocacy.collector.ManualCheckExclusions(List.of()).matches("https://example.com/articles"));
    assertThrows(IllegalArgumentException.class,()->new com.ibm.advocacy.collector.ManualCheckExclusions(List.of("https://user@example.com")));
}
@Test void newlyExcludedSourcesStillRequireMissingOrInvalidNumbersToBeCorrected() throws Exception {
    Run run=run(List.of(blog("a","https://www.infoq.com/a",""),blog("b","https://quarkus.io/blog/b","invalid"),blog("c","https://www.youtube.com/watch?v=OSsatFo8Pmc","-1"),blog("d","https://www.kevindubois.com/d","0")),false);
    pipeline.digital(new PhaseTask(run.id,1));Run saved=workspace.load(run.id);assertEquals(3,saved.issues.size());assertTrue(saved.issues.stream().allMatch(issue->issue.blocking()&&issue.metricType().equals("VIEWS")));
    Data data=pipeline.stage(saved,1,"digital.csv");assertEquals(1,data.rows.size());assertEquals("0",data.rows.getFirst().get(VIEWS));
}
@Test void explicitVerifiedOverridesRemainUsableForExcludedContent() throws Exception {
    Row row=blog("a","https://www.infoq.com/test","1000");Run run=run(List.of(row),true);
    run.measurements.put(Calculations.urlKey(row.get(URL)),new Measurement("1200","VIEWS",row.get(URL),run.createdAt,"Verified override",true));workspace.save(run);workspace.data(run.id,"source",new Data(new ArrayList<>(row.cells.keySet()),List.of(row)));
    pipeline.digital(new PhaseTask(run.id,1));Run current=workspace.load(run.id);assertTrue(current.issues.isEmpty());assertEquals("1200",pipeline.stage(current,1,"digital.csv").rows.getFirst().get(VIEWS));
    pipeline.trailing(new PhaseTask(run.id,4));Run historical=workspace.load(run.id);assertTrue(historical.issues.isEmpty());Row output=pipeline.stage(historical,4,"updated_90-day.csv").rows.getFirst();assertEquals("800",output.get("Current total"));assertEquals("1200",output.get(VIEWS));assertEquals("Workbook content input — not a trailing delta",output.get("Metric basis"));
}

}
