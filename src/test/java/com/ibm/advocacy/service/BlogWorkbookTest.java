package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class BlogWorkbookTest {
    @Inject WorkbookService workbooks;
    @Inject RunService runs;
    @Inject CsvCodec csv;
    @Inject RuntimeWorkspace workspace;
    void row(Sheet sheet,int n,String... values) {var row=sheet.createRow(n);for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}
    byte[] fixture() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet current=book.createSheet("Current month inputs");
            row(current,0,"Date","Author","Tool","Type of activity","Name of activity","Link / URL to resource");
            row(current,1,"2026-10-05","Daniel","Quarkus","Customer meeting","Current activity","");
            Sheet history=book.createSheet("Previous months inputs");
            row(history,0,"Date","Author","Tool","Type of activity","Name of activity","Link / URL to resource","Current total");
            row(history,1,"2026-09-01","Historical author","Quarkus","Recorded video","Historical activity","https://example.com/history","70");
            Sheet markus=book.createSheet("Markus blog data");
            row(markus,0,"Date","Name","Tool","Strategic initiative","Title","Event","Collection","Type","Trailing","","Current","Previous","Views","","Link");
            row(markus,1,"2026-10-01","Markus; Alex","IBM Runtimes","Java","café 日本語","","","","Yes","","","","24","","https://example.com/markus");
            Cell date=markus.getRow(1).getCell(0);date.setCellValue(LocalDate.of(2026,10,1));CellStyle style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("m/d/yyyy"));date.setCellStyle(style);
            markus.getRow(1).getCell(12).setCellFormula("12+12");
            row(markus,2,"2026-09-30","Markus","IBM Runtimes","Java","September post","","","Blog post / slide shares / .io updates","","","","","10","","https://example.com/september");
            row(markus,3,"2026-11-01","Markus","IBM Runtimes","Java","Future post","","","","","","","","15","","https://example.com/future");
            Sheet elder=book.createSheet("Elder blog data");
            row(elder,0,"Month","Name","Tool","","Title","","","Type","","","","","views","","blog");
            row(elder,1,"October 2026","Elder Moraes","IBM Runtimes","","October post","","","","","","","","31","","https://example.com/elder");
            row(elder,2,"2026-09","Elder Moraes","IBM Runtimes","","Older post","","","","","","","","19","","https://example.com/older");
            elder.getRow(2).getCell(0).setCellValue(LocalDate.of(2026,9,15));elder.getRow(2).getCell(0).setCellStyle(style);
            row(elder,3,"","","","","","","","","","","","","9","","/undated-post");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(out);return out.toByteArray();
        }
    }
    @Test void inspectionRecognizesBlogSchemasAndKeepsThemOutOfHistoryDefaults() throws Exception {
        byte[] bytes=fixture();var inspection=workbooks.inspect(bytes);
        assertEquals("Current month inputs",inspection.suggestedCurrentSheet());assertEquals(List.of("Previous months inputs"),inspection.suggestedHistorySheets());
        var elder=inspection.sheets().stream().filter(s->s.name().equals("Elder blog data")).findFirst().orElseThrow();
        assertTrue(elder.eligible());assertTrue(elder.blog());assertEquals(2,elder.rows());assertEquals(1,elder.undatedRows());assertEquals(1,elder.monthCounts().get("2026-10"));
        assertTrue(workbooks.inspect(bytes).sheets().get(3).blog());
    }
    @Test void matchingBlogsAppendWithSourceValuesAndDoNotLeakIntoHistory() throws Exception {
        var prepared=workbooks.prepare(new WorkbookRequest(fixture(),"2026-10","Current month inputs",List.of("Previous months inputs")));
        assertEquals(3,prepared.current().rows.size());assertEquals(1,prepared.trailing().rows.size());
        assertEquals(Map.of("Markus blog data",1,"Elder blog data",1),prepared.blogRows());assertEquals(1,prepared.blogSkippedRows().get("Elder blog data"));
        var markus=prepared.current().rows.stream().filter(r->r.get("Source sheet").equals("Markus blog data")).findFirst().orElseThrow();
        assertEquals("Markus; Alex",markus.get("Author of activity"));assertEquals("café 日本語",markus.get("Name of activity"));assertEquals("24",markus.get("Views / attendance"));assertEquals("https://example.com/markus",markus.get(MetricsSchema.URL));assertEquals("Blog post / slide shares / .io updates",markus.get("Type of activity"));assertEquals("2",markus.get("Source row"));
        var elder=prepared.current().rows.getLast();assertEquals("2026-10-01",elder.get("Date of activity"));assertEquals("Month only",elder.get("Source date precision"));assertEquals("Elder Moraes",elder.get("Author of activity"));
        Data csvRows=csv.read(csv.write(prepared.current()),false);assertEquals("Daniel",Calculations.finalRow(csvRows.rows.getFirst()).get("Author"));assertEquals("Daniel",Calculations.split(csvRows.rows.getFirst(),null).getFirst().get("Author of activity"));
        assertEquals(2,Calculations.split(csvRows.rows.get(1),"Views / attendance").size());
        assertFalse(csvRows.rows.stream().anyMatch(r->r.get(MetricsSchema.URL).equals("/undated-post")));
    }
@Test void workbookAgentPersistsAppendCountsAndDownloadContainsBothBlogSources() throws Exception {
    for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),java.nio.file.Files.readAllBytes(java.nio.file.Path.of("runtimes",name)));
    String id=runs.createWorkbook(fixture(),"blogs.xlsx","2026-10","Current month inputs",List.of("Previous months inputs")).id;
    Run stored=workspace.load(id);
    assertEquals(1,((Map<?,?>)stored.workbook.get("blogRows")).get("Markus blog data"));assertEquals(1,((Map<?,?>)stored.workbook.get("blogSkippedRows")).get("Elder blog data"));
    byte[] download=given().pathParam("name","AvocadoBot 2.csv").get("/api/runs/"+id+"/inputs/{name}/download").then().statusCode(200).extract().asByteArray();
    Data data=csv.read(download,false);assertEquals(3,data.rows.size());assertEquals("Markus blog data",data.rows.get(1).get("Source sheet"));assertEquals("Elder blog data",data.rows.get(2).get("Source sheet"));
}
    @Test void earlierMonthCanUseBlogEntriesWhenPrimarySheetHasNoRowsThatMonth() throws Exception {
        var prepared=workbooks.prepare(new WorkbookRequest(fixture(),"2026-09","Current month inputs",List.of()));
        assertEquals(2,prepared.current().rows.size());assertEquals("2026-07-01",prepared.windowStart());assertEquals("2026-09-30",prepared.windowEnd());assertNull(prepared.trailing());
        assertTrue(prepared.current().rows.stream().allMatch(r->r.get("Date of activity").startsWith("2026-09")));
        var elder=prepared.current().rows.getLast();assertEquals("2026-09-01",elder.get("Date of activity"));assertEquals("Month only",elder.get("Source date precision"));
    }
    @Test void hiddenBlogsAreExcludedAndManualBlogSelectionCannotDuplicateAppend() throws Exception {
        assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(fixture(),"2026-10","Elder blog data",List.of())));
        assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(fixture(),"2026-10","Current month inputs",List.of("Markus blog data"))));
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture()));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            book.setSheetHidden(book.getSheetIndex("Elder blog data"),true);book.write(out);
            var prepared=workbooks.prepare(new WorkbookRequest(out.toByteArray(),"2026-10","Current month inputs",List.of()));assertEquals(2,prepared.current().rows.size());assertFalse(prepared.blogRows().containsKey("Elder blog data"));
        }
    }
    @Test void invalidDatedBlogsAreRejectedRatherThanSilentlyDropped() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture()));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            book.getSheet("Elder blog data").getRow(1).getCell(0).setCellValue("not a month");book.write(out);
            var error=assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(out.toByteArray(),"2026-10","Current month inputs",List.of())));assertTrue(error.getMessage().contains("Elder blog data row 2"));
        }
    }
}
