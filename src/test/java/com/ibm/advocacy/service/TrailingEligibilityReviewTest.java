package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TrailingEligibilityReviewTest {
    @Inject WorkbookService workbooks;
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject Pipeline pipeline;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    void row(Sheet sheet,int n,String... values){var row=sheet.createRow(n);for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}
    byte[] workbook() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet current=book.createSheet("Current month inputs");row(current,0,"Date of activity","Author","Tool","Type of activity","Name of activity",VIEWS,"Duration (in hours)",URL);
            row(current,1,"2026-09-11","Daniel;Kevin","A;B;C","Customer meeting","Global Quarkus Adoption", "4","1","");
            row(current,2,"2026-09-11","Daniel","A;B","Conference keynote or breakout (proprietary or 3rd party)","Build Your Own Claude Code","150","1","");
            row(current,3,"2026-09-12","Daniel","A","Recorded video","Excluded digital content","1200","1","https://thedevconf.com/event");
            Sheet history=book.createSheet("September inputs");row(history,0,"Date","Author","Tool","Type of activity","Name of activity",VIEWS,"Current total",URL,"Trailing 90-day");
            row(history,1,"2026-09-11","Daniel","A","Customer meeting","Global Quarkus Adoption","0.6667","","","No");
            row(history,2,"2026-09-11","Daniel","A","Conference keynote or breakout (proprietary or 3rd party)","Build Your Own Claude Code","75","","","No");
            row(history,3,"2026-08-12","Daniel","A","Recorded video","Excluded historic content","1000","","https://ec.yourlearning.ibm.com/activity","Yes");
            row(history,4,"2026-08-13","Daniel","A","Recorded video","Excluded historic conference","500","","https://thedevconf.com/event","Yes");
            book.write(out);return out.toByteArray();
        }
    }
    Row history(String id,String flag,String baseline,String views,String url) {return new Row(id,Map.of("Type of activity","Recorded video","Name of activity",id,"Trailing 90-day",flag,"Current total",baseline,VIEWS,views,URL,url,"Source sheet","September inputs","Author","Daniel","Tool","Quarkus"));}
    Run stored(List<Row> rows) throws Exception {
        Run run=new Run();run.id=UUID.randomUUID().toString();run.createdAt=Instant.now().toString();run.trailing=true;run.phase=4;run.status="AWAITING_APPROVAL";run.workbook.put("missingBaselines",102);run.workbook.put("historyFilename","Metrics.xlsx");workspace.save(run);workspace.data(run.id,"trailing-source",new Data(new ArrayList<>(rows.getFirst().cells.keySet()),rows));return run;
    }
    Run await(String id,String status) throws Exception {for(int n=0;n<400;n++){Run run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timeout");}
    @Test void preparationExcludesNonTrailingAttributedRowsAndPreservesActualBaselineCells() throws Exception {
        var result=workbooks.prepare(new WorkbookRequest(workbook(),"2026-09","Current month inputs",List.of("September inputs")));
        assertEquals(3,result.current().rows.size());assertEquals("4",result.current().rows.getFirst().get(VIEWS));assertEquals("1",result.current().rows.getFirst().get("Duration (in hours)"));assertEquals(2,result.trailing().rows.size());assertEquals(0,result.missingBaselines());assertTrue(result.trailing().rows.stream().allMatch(row->row.get("Current total").isBlank()));assertTrue(result.trailing().rows.stream().noneMatch(row->row.get("Name of activity").contains("Global Quarkus")));
    }
    @Test void excludedContentHasNoFollowUpOrBaselineIssuesThroughEveryPhaseAndConservesCurrentActivities() throws Exception {
        Run run=runs.createWorkbook(workbook(),"fixture.xlsx","2026-09","Current month inputs",List.of("September inputs"));runs.start(run.id,true);
        for(int phase=1;phase<=4;phase++){Run review=await(run.id,"AWAITING_APPROVAL");assertEquals(phase,review.phase);assertTrue(review.issues.isEmpty());assertEquals(0,review.artifacts.get("follow-up.csv").rows());runs.approve(run.id,phase,review.version,RunService.digest(review),false);}
        Run completed=await(run.id,"COMPLETED");Data current=runs.artifactData(run.id,"current.csv",null);assertEquals(9,current.rows.size());assertEquals(0,completed.artifacts.get("follow-up.csv").rows());assertEquals(2,completed.artifacts.get("updated_90-day.csv").rows());assertEquals("1200",current.rows.stream().filter(row->row.get("Name of activity").equals("Excluded digital content")).findFirst().orElseThrow().get(VIEWS));
    }
    @Test void retryFiltersLegacyNoRowsWithoutChangingImmutableHistoricalSource() throws Exception {
        Run run=stored(List.of(history("no","No","Missing or invalid baseline — manual review required","0.6667",""),history("excluded","Yes","","1000","https://ec.yourlearning.ibm.com/activity"),history("collect","Yes","","1000","https://blog.example.com/post")));
        byte[] original=Files.readAllBytes(workspace.runPath(run.id,"state/trailing-source.json"));pipeline.trailing(new PhaseTask(run.id,4));Run result=workspace.load(run.id);
        assertEquals(2,result.trailingRows);assertEquals(1,((Number)result.workbook.get("missingBaselines")).intValue());assertTrue(result.issues.stream().noneMatch(issue->issue.rowId().equals("no")||issue.rowId().equals("excluded")));assertEquals(1,result.issues.stream().filter(issue->issue.metricType().equals("BASELINE")).count());assertTrue(result.issues.stream().noneMatch(issue->issue.reason().contains("Invalid number: Missing")));assertEquals(2,pipeline.stage(result,4,"updated_90-day.csv").rows.size());assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"state/trailing-source.json")));
    }
    @Test void missingBaselineCellsStayBlankInsteadOfContainingMessages() throws Exception {
        byte[] bytes=workbook();try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            book.getSheet("September inputs").getRow(3).getCell(7).setCellValue("https://blog.example.com/post");book.write(out);var prepared=workbooks.prepare(new WorkbookRequest(out.toByteArray(),"2026-09","Current month inputs",List.of("September inputs")));assertEquals(1,prepared.missingBaselines());assertEquals("",prepared.trailing().rows.getFirst().get("Current total"));
        }
        Run run=stored(List.of(history("yes","Yes","Missing or invalid baseline — manual review required","10","")));pipeline.trailing(new PhaseTask(run.id,4));Run result=workspace.load(run.id);assertTrue(result.issues.stream().anyMatch(issue->issue.reason().equals("Previous baseline is missing in the historical metrics workbook")));assertEquals("",pipeline.stage(result,4,"updated_90-day.csv").rows.getFirst().get("Current total"));
    }
    @Test void reviewContextKeepsHistoricalSharesSeparateFromRawCurrentAttendance() throws Exception {
        Row row=history("September inputs:70/0","Yes","","0.6667","");Run run=stored(List.of(row));workspace.data(run.id,"source",new Data(List.of(VIEWS,"Duration (in hours)"),List.of(new Row("current",Map.of(VIEWS,"4","Duration (in hours)","1")))));
        var context=runs.measurementInput(run.id,row.id,"BASELINE");assertEquals("",context.value());assertEquals("0.6667",context.originalViews());assertEquals("",context.originalDuration());assertEquals("Metrics.xlsx",context.source().workbook());assertEquals("September inputs",context.source().sheet());assertEquals(row.id,context.source().rowId());assertEquals("Daniel",context.source().author());assertEquals("Quarkus",context.source().tool());
    }
    @Test void fractionalExcludedViewsRemainValidAndAreNotRoundedUpOrDropped() throws Exception {
        Run run=stored(List.of(history("fraction","Yes","","0.6667","https://thedevconf.com/event")));pipeline.trailing(new PhaseTask(run.id,4));Run result=workspace.load(run.id);assertTrue(result.issues.isEmpty());assertEquals("0.6667",pipeline.stage(result,4,"updated_90-day.csv").rows.getFirst().get(VIEWS));
    }
    @Test void flagRulesRespectOptOutAndDigitalFallbackWithoutBreakingLegacyInputs() {
        assertFalse(TrailingEligibility.includes(history("n","No","","0","")));assertTrue(TrailingEligibility.includes(history("y","Yes","","0","")));assertTrue(TrailingEligibility.includes(history("blank","","","0","")));
        Row person=history("p","","","1","");person.cells.put("Type of activity","Customer meeting");assertFalse(TrailingEligibility.includes(person));person.cells.remove("Trailing 90-day");assertTrue(TrailingEligibility.includes(person));
    }
@Test void cachedExcludedMeasurementsCannotReintroduceBaselineIssuesAndManualOverridesConserveShares() throws Exception {
    Run run=stored(List.of(history("automatic","Yes","","1000","https://www.the-main-thread.com/post"),history("one","Yes","","500","https://eldermoraes.com/post"),history("two","Yes","","500","https://eldermoraes.com/post")));
    run.measurements.put("https://www.the-main-thread.com/post",new Measurement("9999","VIEWS","",run.createdAt,"Old automatic collection",false));run.measurements.put("https://eldermoraes.com/post",new Measurement("1200","VIEWS","",run.createdAt,"Verified input",true));workspace.save(run);
    pipeline.trailing(new PhaseTask(run.id,4));Run result=workspace.load(run.id);assertTrue(result.issues.isEmpty());Data output=pipeline.stage(result,4,"updated_90-day.csv");assertEquals(List.of("1000","600","600"),output.rows.stream().map(row->row.get(VIEWS)).toList());assertTrue(output.rows.stream().allMatch(row->row.get("Current total").isBlank()&&row.get("Previous total").isBlank()));
}
@Test void unsplitHistoricalContentAllocatesSuppliedViewsWithoutDuplicatingTotals() throws Exception {
    try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(workbook()));ByteArrayOutputStream out=new ByteArrayOutputStream()){
        book.getSheet("September inputs").getRow(3).getCell(1).setCellValue("A;B");book.getSheet("September inputs").getRow(3).getCell(2).setCellValue("X;Y");book.write(out);
        var prepared=workbooks.prepare(new WorkbookRequest(out.toByteArray(),"2026-09","Current month inputs",List.of("September inputs")));assertEquals(5,prepared.trailing().rows.size());assertEquals(4,prepared.trailing().rows.stream().filter(row->row.get(VIEWS).equals("250")).count());assertEquals(0,prepared.missingBaselines());
    }
}

}
