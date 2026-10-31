package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BoothAttendanceTest {
    @Inject RunService runs;@Inject RuntimeWorkspace workspace;@Inject Pipeline pipeline;@Inject MonthlyReporting reporting;@Inject ObjectMapper mapper;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    Run fixture() throws Exception {
        String csv="Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Duration (in hours),Link / URL to resource\n2026-10-28,Ryan;Kevin,IBM Bob;IBM Runtimes,Booth activation,Booth duty,5,2,\n2026-10-28,Ryan,IBM Bob,Short shift,Booth duty,5,.5,\n2026-10-28,Ryan,IBM Bob,Zero attendees,Booth duty,0,2,\n2026-10-28,Ryan,IBM Bob,Zero hours,Booth duty,5,0,\n2026-10-28,Ryan,IBM Bob,Missing hours,Booth duty,5,,\n2026-10-28,Ryan,IBM Bob,Invalid attendees,Booth duty,invalid,2,\n";
        Run run=runs.create(csv.getBytes(StandardCharsets.UTF_8),null);run.workbook.put("month","2026-10");run.workbook.put("reportingPolicy","MONTH_END");run.workbook.put("historySheets",List.of());
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            var history=book.createSheet("October inputs");String[] headers={"Date","Author","Tool","Name of activity","Type of activity",URL,"Current total","Previous total","Trailing 90-day",VIEWS};var head=history.createRow(0);for(int i=0;i<headers.length;i++)head.createCell(i).setCellValue(headers[i]);var sample=history.createRow(1);String[] values={"2026-10-01","Fixture","IBM Bob","Fixture","Booth duty","","0","0","No","0"};for(int i=0;i<values.length;i++)sample.createCell(i).setCellValue(values[i]);
            var model=book.createSheet("DA metric model 2026");var modelHead=model.createRow(0);String[] modelHeaders={"Activities","Unit Measure","Weight","Trailing 90 days?"};for(int i=0;i<modelHeaders.length;i++)modelHead.createCell(i).setCellValue(modelHeaders[i]);var row=model.createRow(1);row.createCell(0).setCellValue("Booth duty");row.createCell(1).setCellValue("Lead total");row.createCell(2).setCellValue(10);row.createCell(3).setCellValue("No");book.write(out);workspace.bytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"),out.toByteArray());
        }
        workspace.save(run);return run;
    }
    @Test void positiveInputsUseActualHoursAndRejectZeroMissingNegativeOrInvalidValues() {
        Row row=new Row("source:1",Map.of("Type of activity","Booth duty",VIEWS,"5","Duration (in hours)","2"));
        assertEquals("10",Calculations.format(Calculations.attendance(row)));row.cells.put("Duration (in hours)",".5");assertEquals("2.5",Calculations.format(Calculations.attendance(row)));
        for(String invalid:List.of("0","-1","","bad")){row.cells.put("Duration (in hours)",invalid);assertFalse(BoothAttendance.eligible(row));assertThrows(IllegalArgumentException.class,()->Calculations.attendance(row));}
        row.cells.put("Duration (in hours)","2");for(String invalid:List.of("0","-1","","bad")){row.cells.put(VIEWS,invalid);assertFalse(BoothAttendance.eligible(row));}
    }
    @Test void pipelineRoutesPositiveBoothsToInPersonAndAllocatesExactlyOnce() throws Exception {
        Run run=fixture();workspace.data(run.id,"phase-1-digital",new Data());workspace.data(run.id,"phase-1-follow-up",new Data());
        pipeline.attendance(new PhaseTask(run.id,2));Data person=workspace.data(run.id,"phase-2-in-person");
        assertEquals(2,person.rows.size());assertEquals(List.of("10","2.5"),person.rows.stream().map(row->row.get("Attendance hours")).toList());assertEquals(4,workspace.data(run.id,"phase-2-follow-up").rows.size());assertTrue(workspace.load(run.id).issues.isEmpty());
        pipeline.attribution(new PhaseTask(run.id,3));Data shares=workspace.data(run.id,"phase-3-in-person");assertEquals(5,shares.rows.size());
        assertTrue(shares.rows.stream().allMatch(row->row.get("Attendance hours").equals("2.5")));assertEquals("12.5",Calculations.format(shares.rows.stream().map(row->Calculations.number(row.get(VIEWS))).reduce(BigDecimal.ZERO,BigDecimal::add)));
        Set<String> unresolved=new HashSet<>();workspace.data(run.id,"phase-3-follow-up").rows.forEach(row->unresolved.add(row.id));
        Data all=Data.output(new Data(),java.util.stream.Stream.concat(shares.rows.stream(),workspace.data(run.id,"phase-3-follow-up").rows.stream()).map(Calculations::finalRow).toList());
        Data reported=reporting.currentPublication(run,all,unresolved);assertEquals(5,reported.rows.stream().filter(row->row.get("Reporting status").equals("Included")).count());
        assertEquals("125",Calculations.format(reported.rows.stream().filter(row->row.get("Reporting status").equals("Included")).map(row->Calculations.number(row.get("Impact hours"))).reduce(BigDecimal.ZERO,BigDecimal::add)));
    }
    @Test void savedReportsExportsAndPublicationReconcileSourceAndKeepPriorConfirmationsForAudit() throws Exception {
        Run run=fixture();Data source=workspace.data(run.id,"source");List<Row> old=new ArrayList<>();for(Row row:source.rows)old.addAll(Calculations.split(row,null).stream().map(Calculations::finalRow).toList());
        Data legacy=Data.output(new Data(),old);byte[] original=mapper.writeValueAsBytes(legacy);run.status="COMPLETED";run.phase=7;run.version=1;run.artifacts.put("current.csv",new Artifact("current.csv",1,7,old.size(),"fixture"));run.artifacts.put("follow-up.csv",new Artifact("follow-up.csv",1,5,old.size(),"fixture"));
        String id=old.getFirst().id;MonthEndValue previous=new MonthEndValue("0",ReportingPeriod.cutoff(run),"Earlier zero","2026-10-30T00:00:00Z");run.monthEndValues.put("current:"+id,previous);
        workspace.bytes(workspace.runPath(run.id,"output/1/current.json"),original);workspace.bytes(workspace.runPath(run.id,"output/1/follow-up.json"),original);workspace.data(run.id,"phase-5-follow-up",legacy);workspace.save(run);
        assertEquals("125",runs.report(run.id,"combined").impact());assertEquals("125",runs.report(run.id,"combined").impact());assertEquals(5,runs.report(run.id,"combined").included());
        Data export=runs.artifactData(run.id,"current.csv",null);Row repaired=export.rows.getFirst();assertEquals("25",repaired.get("Impact hours"));assertEquals("Attendance hours",repaired.get("Metric unit"));assertEquals("0",repaired.get("Previous manual booth total"));assertEquals(previous,workspace.load(run.id).monthEndValues.get("current:"+id));
        Set<String> follow=new HashSet<>();old.forEach(row->follow.add(row.id));Data publication=reporting.currentPublication(run,legacy,follow);assertEquals("25",publication.rows.getFirst().get("Impact hours"));
        assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"output/1/current.json")));assertEquals("5",workspace.data(run.id,"source").rows.getFirst().get(VIEWS));
        // Historical snapshots are already attributed and keep the old one-time eligibility.
        var historical=MonthlyReporting.transform(run,legacy,false,reporting.context(run),Set.of());assertTrue(historical.rows.stream().allMatch(row->row.get("Reporting status").equals("Excluded")));
    }
}
