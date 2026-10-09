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
        for(Row row:data.rows){assertEquals("800",row.get("Current total"));assertEquals("800",row.get("Previous total"));assertEquals("Workbook blog input — not a trailing delta",row.get("Metric basis"));assertTrue(row.get(NOTE).contains("no fresh lifetime total"));}
    }
    @Test void historicalInvalidViewsAndBaselinesStillRequireCorrection() throws Exception {
        Row row=blog("a","https://eldermoraes.com/post","bad");row.cells.put("Current total","bad");Run run=run(List.of(row),true);
        pipeline.trailing(new PhaseTask(run.id,4));Run saved=workspace.load(run.id);assertEquals(Set.of("VIEWS","BASELINE"),new HashSet<>(saved.issues.stream().map(Issue::metricType).toList()));assertTrue(saved.issues.stream().allMatch(Issue::blocking));
    }
}
