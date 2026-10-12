package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ParticipationAttendanceTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject ObjectMapper mapper;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    Run fixture(String count,String duration,String tools) throws Exception {
        String csv="Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Duration (in hours),Link / URL to resource\n2026-09-10,Daniel Oh;Kevin Dubois,"+tools+",TAG meeting,Standard participation,"+count+","+duration+",\n";
        Run run=runs.create(csv.getBytes(StandardCharsets.UTF_8),null);run.workbook.put("month","2026-09");run.workbook.put("reportingPolicy","MONTH_END");run.workbook.put("filename","AvocadoBot.xlsx");run.workbook.put("historySheets",List.of());
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            var activities=book.createSheet("September inputs");var activityHead=activities.createRow(0);String[] activityHeaders={"Date","Author","Tool","Name of activity","Type of activity","Link / URL to resource","Current total","Previous total","Trailing 90-day","Views / attendance"};for(int i=0;i<activityHeaders.length;i++)activityHead.createCell(i).setCellValue(activityHeaders[i]);
            var historical=activities.createRow(1);String[] historicalValues={"2026-09-01","Fixture Author","IBM Bob","Historical fixture","Standard participation","","1","0","No","1"};for(int i=0;i<historicalValues.length;i++)historical.createCell(i).setCellValue(historicalValues[i]);
            var model=book.createSheet("DA metric model 2026");var head=model.createRow(0);String[] headers={"Activities","Unit Measure","Weight","Trailing 90 days?"};for(int i=0;i<headers.length;i++)head.createCell(i).setCellValue(headers[i]);var row=model.createRow(1);row.createCell(0).setCellValue("Standard participation");row.createCell(1).setCellValue("number of standards");row.createCell(2).setCellValue(100);row.createCell(3).setCellValue("No");book.write(out);workspace.bytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"),out.toByteArray());
        }
        workspace.save(run);return run;
    }
    Run await(String id,String status) throws Exception {for(int i=0;i<400;i++){Run run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timed out waiting for "+status);}
    void approve(Run run) throws Exception {runs.approve(run.id,run.phase,run.version,RunService.digest(run),false);}
    @Test void allPhasesAutomaticallyShareOneParticipationUnitAcrossAuthorsAndToolsWithoutFollowup() throws Exception {
        Run run=fixture("15","2","IBM Bob;IBM Runtimes");runs.start(run.id,true);Run first=await(run.id,"AWAITING_APPROVAL");assertEquals(1,first.phase);assertTrue(first.issues.isEmpty());assertEquals(0,first.artifacts.get("follow-up.csv").rows());approve(first);
        Run second=await(run.id,"AWAITING_APPROVAL");assertEquals(2,second.phase);assertTrue(second.issues.isEmpty());assertEquals("1",runs.artifactData(run.id,"in-person.csv",null).rows.getFirst().get("Attendance hours"));approve(second);
        Run third=await(run.id,"AWAITING_APPROVAL");assertEquals(3,third.phase);Data attributed=runs.artifactData(run.id,"in-person.csv",null);assertEquals(4,attributed.rows.size());assertTrue(attributed.rows.stream().allMatch(row->row.get("Attendance hours").equals("0.25")));approve(third);
        Run complete=await(run.id,"COMPLETED");assertEquals(0,complete.artifacts.get("follow-up.csv").rows());var report=runs.report(run.id,"combined");assertEquals(4,report.included());assertEquals(0,report.excluded());assertEquals("100",report.impact());assertEquals(0,BigDecimal.ONE.compareTo(report.details().rows.stream().map(row->Calculations.number(row.get("Exact reporting metric"))).reduce(BigDecimal.ZERO,BigDecimal::add)));assertTrue(report.details().rows.stream().allMatch(row->row.get("Reporting action").isBlank()));
        assertEquals("15",workspace.data(run.id,"source").rows.getFirst().get(VIEWS));assertEquals("100",runs.report(run.id,"combined").impact());
    }
@Test void sourceAttendanceAndDurationAreEvidenceNotParticipationMultipliers() throws Exception {
    Run run=fixture("invalid","invalid","IBM Bob");runs.start(run.id,true);approve(await(run.id,"AWAITING_APPROVAL"));Run second=await(run.id,"AWAITING_APPROVAL");assertEquals(2,second.phase);assertTrue(second.issues.isEmpty());approve(second);approve(await(run.id,"AWAITING_APPROVAL"));await(run.id,"COMPLETED");assertEquals("100",runs.report(run.id,"current").impact());
}
    @Test void existingCompletedUnallocatedFollowupsAreCorrectedOnCopiesWithoutAnotherConfirmation() throws Exception {
        Run run=fixture("15","1","IBM Bob");Data source=workspace.data(run.id,"source");List<Row> rows=Calculations.split(source.rows.getFirst(),null).stream().map(Calculations::finalRow).toList();rows.forEach(row->row.cells.put(NOTE,"Other activity — manual follow-up"));byte[] original=mapper.writeValueAsBytes(Data.output(new Data(),rows));
        run.phase=7;run.version=1;run.status="COMPLETED";run.artifacts.put("current.csv",new Artifact("current.csv",1,7,2,"fixture"));run.artifacts.put("follow-up.csv",new Artifact("follow-up.csv",1,5,2,"fixture"));workspace.bytes(workspace.runPath(run.id,"output/1/current.json"),original);workspace.bytes(workspace.runPath(run.id,"output/1/follow-up.json"),original);
        run.monthEndValues.put("current:c1/0",new MonthEndValue("15","2026-09-30","Old unsplit confirmation","2026-10-11T12:00:00Z"));workspace.save(run);
        var report=runs.report(run.id,"combined");assertEquals(2,report.included());assertEquals(0,report.excluded());assertEquals("100",report.impact());assertTrue(report.details().rows.stream().allMatch(row->row.get("Exact reporting metric").equals("0.5")&&row.get("Reporting action").isBlank()));assertEquals("15",report.details().rows.getFirst().get("Previous manual participation total"));
        Data download=runs.artifactData(run.id,"current.csv",null);assertTrue(download.rows.stream().allMatch(row->row.get(VIEWS).equals("1")&&row.get("Impact hours").equals("50")));assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"output/1/current.json")));assertEquals("15",runs.get(run.id).monthEndValues.get("current:c1/0").value());assertEquals("100",runs.report(run.id,"combined").impact());
    }
}
