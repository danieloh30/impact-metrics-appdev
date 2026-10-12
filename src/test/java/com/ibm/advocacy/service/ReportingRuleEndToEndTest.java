package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

/** All seven agents must preserve explicit monthly evidence without calling collectors. */
@QuarkusTest
class ReportingRuleEndToEndTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;

    @Test void participationAndVerifiedAugustActivitiesConserveSeptemberReportAndExports() throws Exception {
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
        String current="Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Duration (in hours),Link / URL to resource\n2026-09-10,Daniel Oh;Kevin Dubois,IBM Bob;IBM Runtimes,TAG meeting,Standard participation,15,2,\n";
        String past="Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Link / URL to resource,Current total,Previous total,Trailing 90-day,Verified reporting month,Verified workbook current total,Verified workbook previous total,Activity stage\n2026-08-19,Markus Eisele,IBM Bob,Use Bob,Recorded video,3100,https://www.linkedin.com/events/verified-use-bob/,9533,6433,Yes,2026-09,9533,6433,Delivery\n2026-08-24,Markus Eisele,IBM Bob,Token first reported,Recorded video,300,https://www.linkedin.com/events/verified-token/,300,0,Yes,2026-09,300,0,First reported\n";
        Run run=runs.create(current.getBytes(StandardCharsets.UTF_8),past.getBytes(StandardCharsets.UTF_8));
        run.workbook.put("month","2026-09");run.workbook.put("reportingPolicy","MONTH_END");run.workbook.put("filename","AvocadoBot fixture.xlsx");run.workbook.put("historyFilename","Metrics fixture.xlsx");run.workbook.put("historySheets",List.of());
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("September inputs");cells(sheet.createRow(0),"Date","Author","Tool","Name of activity","Type of activity","Link / URL to resource","Current total","Previous total","Trailing 90-day","Views / attendance");
            cells(sheet.createRow(1),"2026-08-19","Markus Eisele","IBM Bob","Use Bob","Recorded video","","9533","6433","Yes","3100");
            var model=book.createSheet("DA metric model 2026");cells(model.createRow(0),"Activities","Unit Measure","Weight","Trailing 90 days?");
            cells(model.createRow(1),"Standard participation","number of standards","100","No");cells(model.createRow(2),"Recorded video","Views","0.8","Yes");
            book.write(out);workspace.bytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"),out.toByteArray());
        }
        workspace.save(run);
        byte[] currentSource=Files.readAllBytes(workspace.runPath(run.id,"state/source.json")),pastSource=Files.readAllBytes(workspace.runPath(run.id,"state/trailing-source.json"));
        runs.start(run.id,true);
        for(int phase=1;phase<=4;phase++) {
            Run ready=await(run.id,"AWAITING_APPROVAL");assertEquals(phase,ready.phase);assertTrue(ready.issues.isEmpty(),ready.issues.toString());
            runs.approve(run.id,phase,ready.version,RunService.digest(ready),false);
        }
        Run complete=await(run.id,"COMPLETED");assertEquals(7,complete.phase);assertTrue(complete.measurements.isEmpty(),"Explicit verified totals must not be replaced by web measurements");
        var report=runs.report(run.id,"combined");assertEquals(6,report.included());assertEquals(0,report.excluded());assertEquals("2820",report.impact());
        var participation=report.details().rows.stream().filter(row->row.type().equals("standard participation")).toList();assertEquals(4,participation.size());assertTrue(participation.stream().allMatch(row->row.get("Exact reporting metric").equals("0.25")&&row.get("Impact hours").equals("25")));
        for(String output:List.of("updated_90-day.csv")) {
            Data data=runs.artifactData(run.id,output,null);assertEquals(2,data.rows.size());
            Row bob=data.rows.stream().filter(row->row.get("Name of activity").equals("Use Bob")).findFirst().orElseThrow();
            assertEquals("2026-08-19",bob.get("Date"));assertEquals("9533",bob.get("Current total"));assertEquals("6433",bob.get("Previous total"));assertEquals("3100",bob.get(VIEWS));assertEquals("2480",bob.get("Impact hours"));assertEquals("",bob.get("Measured at"));
            Row token=data.rows.stream().filter(row->row.get("Name of activity").equals("Token first reported")).findFirst().orElseThrow();assertEquals("2026-08-24",token.get("Date"));assertEquals("0",token.get("Previous total"));assertEquals("240",token.get("Impact hours"));assertEquals("First reported",token.get("Activity stage"));
        }
        Data exported=runs.artifactData(run.id,"current.csv",null);assertEquals(4,exported.rows.size());assertEquals(0,new BigDecimal("100").compareTo(exported.rows.stream().map(row->Calculations.number(row.get("Impact hours"))).reduce(BigDecimal.ZERO,BigDecimal::add)));
        String csv=new String(runs.artifact(run.id,"updated_90-day.csv",null),StandardCharsets.UTF_8);assertTrue(csv.contains("6433"));assertTrue(csv.contains("2480"));assertTrue(csv.contains("2026-08-24"));
        assertArrayEquals(currentSource,Files.readAllBytes(workspace.runPath(run.id,"state/source.json")));assertArrayEquals(pastSource,Files.readAllBytes(workspace.runPath(run.id,"state/trailing-source.json")));assertEquals("2820",runs.report(run.id,"combined").impact());
    }
    private Run await(String id,String status) throws Exception {for(int i=0;i<500;i++){Run run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timed out waiting for "+status);}
    private static void cells(org.apache.poi.ss.usermodel.Row row,String...values){for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);}
}
