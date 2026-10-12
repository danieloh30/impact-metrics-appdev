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
class MonthlyWorkshopAttendanceTest {
    @Inject RunService runs;@Inject RuntimeWorkspace workspace;@Inject ObjectMapper mapper;@Inject MonthlyReporting reporting;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    Run fixture(String duration) throws Exception {
        String csv="Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Duration (in hours),Link / URL to resource\n2026-09-24,Kevin,IBM Runtimes,Quarkus LangChain4j Workshop at Luxottica,Customer workshop,50,3,\n2026-09-25,Ryan;Daniel,Red Hat Runtimes;Red Hat (other tech),Customer Workshops - Monthly RH Sales User Experiences,Customer workshop,753,"+duration+",\n2026-09-25,Ryan,IBM Runtimes,Customer Workshops - Monthly IBM Sales User Experiences,Customer workshop,299,"+duration+",\n";
        Run run=runs.create(csv.getBytes(StandardCharsets.UTF_8),null);run.workbook.put("month","2026-09");run.workbook.put("reportingPolicy","MONTH_END");run.workbook.put("filename","AvocadoBot.xlsx");run.workbook.put("historySheets",List.of());
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            var history=book.createSheet("September inputs");String[] historyHeaders={"Date","Author","Tool","Name of activity","Type of activity",URL,"Current total","Previous total","Trailing 90-day",VIEWS};var historicalHead=history.createRow(0);for(int i=0;i<historyHeaders.length;i++)historicalHead.createCell(i).setCellValue(historyHeaders[i]);String[] historicalValues={"2026-09-01","Fixture author","IBM Runtimes","Historical fixture","Customer workshop","","0","0","No","0"};var historicalRow=history.createRow(1);for(int i=0;i<historicalValues.length;i++)historicalRow.createCell(i).setCellValue(historicalValues[i]);var model=book.createSheet("DA metric model 2026");var head=model.createRow(0);String[] headers={"Activities","Unit Measure","Weight","Trailing 90 days?"};for(int i=0;i<headers.length;i++)head.createCell(i).setCellValue(headers[i]);var row=model.createRow(1);row.createCell(0).setCellValue("Customer workshop");row.createCell(1).setCellValue("Attendance hours");row.createCell(2).setCellValue(10);row.createCell(3).setCellValue("No");book.write(out);workspace.bytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"),out.toByteArray());
        }workspace.save(run);return run;
    }
    Run await(String id,String status) throws Exception {for(int i=0;i<500;i++){Run run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timed out waiting for "+status);}
    void approve(Run run) throws Exception {runs.approve(run.id,run.phase,run.version,RunService.digest(run),false);}
    @Test void allPhasesConserveMonthlyRollupsAndKeepOrdinaryWorkshopDuration() throws Exception {
        Run run=fixture("3");runs.start(run.id,true);approve(await(run.id,"AWAITING_APPROVAL"));Run second=await(run.id,"AWAITING_APPROVAL");assertEquals(2,second.phase);Data person=runs.artifactData(run.id,"in-person.csv",null);assertEquals(List.of("150","753","299"),person.rows.stream().map(row->row.get("Attendance hours")).toList());assertTrue(second.issues.isEmpty());approve(second);
        Run third=await(run.id,"AWAITING_APPROVAL");assertEquals(6,third.artifacts.get("in-person.csv").rows());Data shares=runs.artifactData(run.id,"in-person.csv",null);assertTrue(shares.rows.stream().filter(MonthlyWorkshopAttendance::matches).filter(row->row.get("Name of activity").contains("Monthly RH")).allMatch(row->row.get("Attendance hours").equals("188.25")&&row.get(VIEWS).equals("189")));approve(third);await(run.id,"COMPLETED");
        var report=runs.report(run.id,"combined");assertEquals("12020",report.impact());assertEquals(6,report.included());assertEquals("1202",report.activity().getFirst().metrics());assertEquals("12020",runs.report(run.id,"combined").impact());assertEquals("753",workspace.data(run.id,"source").rows.get(1).get(VIEWS));
        Data export=runs.artifactData(run.id,"current.csv",null);assertTrue(export.rows.stream().filter(row->row.get("Name of activity").contains("Monthly RH")).allMatch(row->row.get(VIEWS).equals("189")&&row.get("Impact hours").equals("1882.5")));
    }
    @Test void suppliedRollupsDoNotRequireDurationAndInvalidCountsRemainARealCorrection() throws Exception {
        Run run=fixture("invalid");runs.start(run.id,true);approve(await(run.id,"AWAITING_APPROVAL"));Run second=await(run.id,"AWAITING_APPROVAL");assertTrue(second.issues.isEmpty());assertEquals("753",runs.artifactData(run.id,"in-person.csv",null).rows.get(1).get("Attendance hours"));
        Row monthly=workspace.data(run.id,"source").rows.get(1).copy();monthly.cells.put(VIEWS,"-1");assertThrows(IllegalArgumentException.class,()->Calculations.attendance(monthly));monthly.cells.put(VIEWS,"");assertThrows(IllegalArgumentException.class,()->Calculations.attendance(monthly));monthly.cells.put(VIEWS,"753");monthly.cells.put("Name of activity","Other customer workshop");assertThrows(IllegalArgumentException.class,()->Calculations.attendance(monthly));
    }
    @Test void olderInflatedReportsExportsAndPublicationAreRepairedFromImmutableSourceWithoutDividingTwice() throws Exception {
        Run run=fixture("3");List<Row> legacy=new ArrayList<>();for(Row original:workspace.data(run.id,"source").rows){Row input=original.copy();input.cells.put("Attendance hours",Calculations.format(Calculations.number(input.get(VIEWS)).multiply(new BigDecimal("3"))));legacy.addAll(Calculations.split(input,"Attendance hours").stream().map(Calculations::finalRow).toList());}
        byte[] original=mapper.writeValueAsBytes(Data.output(new Data(),legacy));run.status="COMPLETED";run.phase=7;run.version=1;run.artifacts.put("current.csv",new Artifact("current.csv",1,7,6,"fixture"));workspace.bytes(workspace.runPath(run.id,"output/1/current.json"),original);workspace.save(run);
        assertEquals("12020",runs.report(run.id,"combined").impact());assertEquals("12020",runs.report(run.id,"combined").impact());var preview=reporting.currentPublication(run,Data.output(new Data(),legacy),Set.of());assertEquals("12020",Calculations.format(preview.rows.stream().map(row->Calculations.number(row.get("Impact hours"))).reduce(BigDecimal.ZERO,BigDecimal::add)));
        Data export=runs.artifactData(run.id,"current.csv",null);assertEquals(6,export.rows.size());assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"output/1/current.json")));assertEquals("753",workspace.data(run.id,"source").rows.get(1).get(VIEWS));
    }
}
