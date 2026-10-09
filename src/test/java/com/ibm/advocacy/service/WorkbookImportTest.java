package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class WorkbookImportTest {
    @Inject WorkbookService workbooks;
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject CsvCodec csv;
    final String[] headers={"Date of activity","Author of activity","Tool","Type of activity","Name of activity","Current total","Link / URL to resource","Trailing 90-day"};
    @BeforeEach void instructions() throws Exception { for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name))); }
    void row(Sheet sheet,int n,String... values) { var row=sheet.createRow(n);for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]); }
    byte[] fixture(boolean missing) throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet current=book.createSheet("October inputs");row(current,0,"Activity workbook");row(current,2,headers);
            row(current,3,"2026-10-10","Daniel","Quarkus","Customer meeting","October meeting","","","No");
            current.getRow(3).createCell(8).setCellValue(10);current.getRow(2).createCell(8).setCellValue("Views / attendance");
            Sheet history=book.createSheet("Previous months");row(history,0,headers);
            row(history,1,"9/20/2026","A;B","X;Y","Recorded video","café 日本語",missing?"":"80","https://example.com/history","Yes");
            row(history,2,"2026-08-01","A","X","Recorded video","Boundary","0","https://example.com/boundary","");
            row(history,3,"2026-07-31","A","X","Recorded video","Too old","10","https://example.com/old","Yes");
            row(history,4,"2026-10-01","A","X","Recorded video","Current excluded","10","https://example.com/current","Yes");
            row(history,5,"2026-09-01","A","X","Recorded video","Opt out","10","https://example.com/no","No");
            row(history,6,"2026-09-01","A","X","Customer meeting","In person","10","","");
            Sheet notes=book.createSheet("Instructions");row(notes,0,"This is not an activity sheet");
            book.write(out);return out.toByteArray();
        }
    }
    @Test void inspectionFindsSheetsAndPreparationUsesExactWindowAndConservesBaselines() throws Exception {
        byte[] bytes=fixture(false);var inspection=workbooks.inspect(bytes);
        assertEquals("2026-10",inspection.suggestedMonth());assertEquals("October inputs",inspection.suggestedCurrentSheet());
        assertFalse(inspection.sheets().get(2).eligible());
        var result=workbooks.prepare(new WorkbookRequest(bytes,"2026-10","October inputs",List.of("Previous months")));
        assertEquals("2026-08-01",result.windowStart());assertEquals("2026-10-31",result.windowEnd());assertEquals(1,result.current().rows.size());assertEquals(6,result.trailing().rows.size());
        assertEquals(4,result.trailing().rows.stream().filter(r->r.get("Current total").equals("20")).count());
        assertEquals(0,result.missingBaselines());
       assertFalse(result.trailing().rows.stream().anyMatch(r->r.get("Name of activity").equals("Opt out")));
       assertFalse(result.trailing().rows.stream().anyMatch(r->r.get("Name of activity").equals("In person")));
       assertTrue(result.trailing().rows.stream().anyMatch(r->r.get("Name of activity").equals("Current excluded")));
       assertFalse(result.trailing().rows.stream().anyMatch(r->r.get("Name of activity").equals("Too old")));
       var september=workbooks.prepare(new WorkbookRequest(bytes,"2026-09","Previous months",List.of("Previous months")));
       assertEquals("2026-07-01",september.windowStart());assertEquals("2026-09-30",september.windowEnd());
       assertTrue(september.trailing().rows.stream().anyMatch(r->r.get("Name of activity").equals("Too old")));
       assertFalse(september.trailing().rows.stream().anyMatch(r->r.get("Name of activity").equals("Current excluded")));assertEquals("café 日本語",result.trailing().rows.getFirst().get("Name of activity"));
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/workbook-e2e.xlsx"),bytes);
    }
    @Test void workbookAgentPreservesOriginalAndInputsAreDownloadableAsUtf8() throws Exception {
        byte[] bytes=fixture(false);
        String id=runs.createWorkbook(bytes,"activities.xlsx","2026-10","October inputs",List.of("Previous months")).id;
        Run run=runs.get(id);assertEquals("READY",run.status);assertEquals(1,run.currentRows);assertEquals(6,run.trailingRows);
        assertEquals("2026-10",run.workbook.get("month"));assertTrue(run.events.stream().anyMatch(e->e.agent().equals("WorkbookPreparationAgent")));
        assertArrayEquals(bytes,Files.readAllBytes(workspace.runPath(id,"rawdata/original.xlsx")));
        byte[] past=given().get("/api/runs/"+id+"/inputs/trailing_90-day.csv/download").then().statusCode(200).header("Content-Type",org.hamcrest.Matchers.containsString("utf-8")).extract().asByteArray();
        assertTrue(new String(past,StandardCharsets.UTF_8).contains("café 日本語"));
        assertEquals(6,csv.read(past,true,StandardCharsets.UTF_8).rows.size());
        given().get("/api/runs/"+id+"/inputs/secret.csv/download").then().statusCode(400);
        assertFalse(workbooks.inspect(bytes).sheets().isEmpty());
    }
    Run waiting(String id) throws Exception {
        for(int n=0;n<200;n++){Run run=runs.get(id);if(run.status.equals("AWAITING_APPROVAL")){Thread.sleep(25);return runs.get(id);}if(run.status.equals("FAILED_RETRYABLE"))fail(run.error);Thread.sleep(25);}throw new AssertionError("Phase timed out");
    }
    @Test void missingBaselineBlocksApprovalBeforeCurrentMetricIsAvailable() throws Exception {
        Run run=runs.createWorkbook(fixture(true),"activities.xlsx","2026-10","October inputs",List.of("Previous months"));
        assertEquals(1,run.workbook.get("missingBaselines"));runs.start(run.id,true);
        for(int phase=1;phase<=3;phase++){Run ready=waiting(run.id);assertEquals(phase,ready.phase);runs.approve(run.id,phase,ready.version,RunService.digest(ready),true);}
        Run phase4=waiting(run.id);assertEquals(4,phase4.phase);assertEquals(4,phase4.issues.stream().filter(i->i.metricType().equals("BASELINE")&&i.blocking()).count());
        assertThrows(BadRequestException.class,()->runs.approve(run.id,4,phase4.version,RunService.digest(phase4),true));
        Issue issue=phase4.issues.stream().filter(i->i.metricType().equals("BASELINE")).findFirst().orElseThrow();
        runs.measured(run.id,phase4.version,issue.rowId(),"20","BASELINE","Historical workbook evidence");
        assertEquals(3,waiting(run.id).issues.stream().filter(i->i.metricType().equals("BASELINE")).count());
    }
    @Test void datesCachedFormulaAndSameSheetHistoryAreSupported() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("All activities");row(sheet,0,headers);row(sheet,1,"2026-10-10","A","X","Recorded video","Current","10","https://example.com/current","Yes");
            row(sheet,2,"2026-09-10","A","X","Recorded video","History","","https://example.com/history","Yes");
            Cell date=sheet.getRow(2).getCell(0);date.setCellValue(LocalDate.of(2026,9,10));CellStyle style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("m/d/yyyy"));date.setCellStyle(style);
            sheet.getRow(2).getCell(5).setCellFormula("40+40");book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(out);
            var prepared=workbooks.prepare(new WorkbookRequest(out.toByteArray(),"2026-10","All activities",List.of("All activities")));
            assertEquals(1,prepared.current().rows.size());assertEquals(2,prepared.trailing().rows.size());assertEquals("80",prepared.trailing().rows.getLast().get("Current total"));assertEquals(0,prepared.missingBaselines());
        }
    }
    @Test void invalidSelectionsFilesAndDatesAreRejected() throws Exception {
        byte[] bytes=fixture(false);
        assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(bytes,"bad","October inputs",List.of())));
        assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(bytes,"2026-10","missing",List.of())));
        assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(bytes,"2026-10","October inputs",List.of("Previous months","Previous months"))));
        assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(bytes,"2025-01","October inputs",List.of())));
        assertThrows(BadRequestException.class,()->workbooks.inspect("broken".getBytes()));
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            book.getSheet("October inputs").getRow(3).getCell(0).setCellValue("not a date");book.write(out);
            assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(out.toByteArray(),"2026-10","October inputs",List.of())));
        }
        Run currentOnly=runs.createWorkbook(bytes,"current.xlsx","2026-10","October inputs",List.of());assertFalse(currentOnly.trailing);
        given().get("/api/runs/"+currentOnly.id+"/inputs/trailing_90-day.csv/download").then().statusCode(404);
    }
}
