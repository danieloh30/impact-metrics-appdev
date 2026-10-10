package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

@QuarkusTest
class WorkbookPipelineTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject CsvCodec csv;
    @BeforeEach void instructions() throws Exception {for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));}
    void row(Sheet sheet,int n,String... values){var row=sheet.createRow(n);for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}
    byte[] workbook() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("Current month inputs");
            row(sheet,0,"Date","Author","Tool","Type of activity","Name of activity",URL,VIEWS,"Duration (in hours)");
            row(sheet,1,"2026-10-01","Oh, Daniel\nAlex","Quarkus\nIBM Bob","Recorded video","Excel, cells remain intact","","","");
            sheet.getRow(1).getCell(6).setCellFormula("500+500");
            var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("\"€ \"#,##0.00"));sheet.getRow(1).getCell(6).setCellStyle(style);
            sheet.getRow(1).getCell(7).setCellValue(2);var hours=book.createCellStyle();hours.setDataFormat(book.createDataFormat().getFormat("0.00\" hours\""));sheet.getRow(1).getCell(7).setCellStyle(hours);
            row(sheet,2,"2026-10-02","Daniel","Quarkus","Customer meeting","Meeting without URL","","","");sheet.getRow(2).getCell(6).setCellValue(10);sheet.getRow(2).getCell(7).setCellValue(2);sheet.getRow(2).getCell(7).setCellStyle(hours);
            row(sheet,3,"2026-10-03","Daniel","Quarkus","Recorded video","Verified zero","","","");sheet.getRow(3).getCell(6).setCellValue(0);sheet.getRow(3).getCell(7).setCellValue(.25);var percent=book.createCellStyle();percent.setDataFormat(book.createDataFormat().getFormat("0%"));sheet.getRow(3).getCell(7).setCellStyle(percent);
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(out);return out.toByteArray();
        }
    }
    Run await(String id,String status) throws Exception {
        for(int n=0;n<300;n++){Run run=runs.get(id);if(run.status.equals(status))return run;if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(20);}throw new AssertionError("Timed out waiting for "+status);
    }
    void approve(Run run) throws Exception {runs.approve(run.id,run.phase,run.version,RunService.digest(run),false);}
    @Test void agentsConsumeStructuredCellsWithStoredNumbersAndStableSheetRowIds() throws Exception {
        Run run=runs.createWorkbook(workbook(),"workbook.xlsx","2026-10","Current month inputs",List.of());
        Data source=workspace.data(run.id,"source");assertEquals("Current month inputs:2",source.rows.getFirst().id);assertEquals("1000",source.rows.getFirst().get(VIEWS));assertEquals("2",source.rows.getFirst().get("Duration (in hours)"));assertEquals("0.25",source.rows.getLast().get("Duration (in hours)"));assertEquals("Excel, cells remain intact",source.rows.getFirst().get("Name of activity"));
        assertEquals("Oh, Daniel\nAlex",source.rows.getFirst().get("Author of activity"));assertEquals(3,csv.read(runs.input(run.id,"AvocadoBot 2.csv"),false).rows.size());
    }
    @Test void suppliedNoUrlMetricsCompleteAllPhasesWithoutCollectionIssuesAndConserveTotals() throws Exception {
        Run run=runs.createWorkbook(workbook(),"workbook.xlsx","2026-10","Current month inputs",List.of());runs.start(run.id,true);
        Run phase1=await(run.id,"AWAITING_APPROVAL");assertEquals(1,phase1.phase);assertTrue(phase1.issues.isEmpty());assertEquals(2,phase1.artifacts.get("digital.csv").rows());assertEquals(0,phase1.artifacts.get("follow-up.csv").rows());
        Data digital=csv.read(runs.artifact(run.id,"digital.csv",null),false);assertTrue(digital.rows.stream().allMatch(r->r.get(NOTE).contains("no URL required")));approve(phase1);
        Run phase2=await(run.id,"AWAITING_APPROVAL");assertTrue(phase2.issues.isEmpty());assertEquals("20",csv.read(runs.artifact(run.id,"in-person.csv",null),false).rows.getFirst().get("Attendance hours"));approve(phase2);approve(await(run.id,"AWAITING_APPROVAL"));
        Run complete=await(run.id,"COMPLETED");Data output=csv.read(runs.artifact(run.id,"current.csv",null),false);assertEquals(6,output.rows.size());assertEquals(4,output.rows.stream().filter(r->r.get("Name of activity").equals("Excel, cells remain intact")&&r.get(VIEWS).equals("250")).count());assertEquals(2,output.rows.stream().filter(r->r.get("Author").equals("Oh, Daniel")).count());assertEquals("0",output.rows.stream().filter(r->r.get("Name of activity").equals("Verified zero")).findFirst().orElseThrow().get("Current total"));assertEquals("COMPLETED",complete.status);
    }
    Run csvRun(String views,String duration,byte[] history) throws Exception {
        String input="Author,Tool,Type of activity,Name of activity,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Recorded video,Input,"+views+","+duration+",\n";
        Run run=runs.create(input.getBytes(java.nio.charset.StandardCharsets.UTF_8),history);runs.start(run.id,true);return await(run.id,"AWAITING_APPROVAL");
    }
    @Test void missingDurationStillRequiresManualCollection() throws Exception {
        Run run=csvRun("10","",null);assertEquals(1,run.issues.size());assertTrue(run.issues.getFirst().reason().contains("No URL"));assertFalse(run.issues.getFirst().blocking());assertEquals(1,run.artifacts.get("follow-up.csv").rows());
    }
    @Test void invalidSuppliedDurationBlocksApprovalAndCanBeCorrected() throws Exception {
        Run run=csvRun("10","invalid",null);assertEquals("DURATION",run.issues.getFirst().metricType());assertTrue(run.issues.getFirst().blocking());assertThrows(BadRequestException.class,()->runs.approve(run.id,1,run.version,RunService.digest(run),true));
        runs.measured(run.id,run.version,"c1","2","DURATION","Verified workbook duration");Run corrected=await(run.id,"AWAITING_APPROVAL");assertTrue(corrected.issues.isEmpty());assertEquals(1,corrected.artifacts.get("digital.csv").rows());
        Run negative=csvRun("-1","1",null);assertTrue(negative.issues.getFirst().blocking());assertEquals("VIEWS",negative.issues.getFirst().metricType());
    }
    @Test void currentInputEvidenceCannotSupplyTrailingLifetimeMeasurement() throws Exception {
        byte[] history="Author,Tool,Type of activity,Name of activity,Current total,Views / attendance,Duration (in hours),Link / URL to resource\nA,X,Recorded video,History,50,10,2,\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Run current=csvRun("10","2",history);assertTrue(current.issues.isEmpty());approve(current);approve(await(current.id,"AWAITING_APPROVAL"));approve(await(current.id,"AWAITING_APPROVAL"));Run trailing=await(current.id,"AWAITING_APPROVAL");assertEquals(4,trailing.phase);assertTrue(trailing.issues.isEmpty());Data data=csv.read(runs.artifact(current.id,"updated_90-day.csv",null),false);assertEquals("10",data.rows.getFirst().get(VIEWS));assertEquals("Supplied historical input — not a trailing delta",data.rows.getFirst().get("Metric basis"));assertEquals("50",data.rows.getFirst().get("Current total"));
    }
}
