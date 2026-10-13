package com.ibm.advocacy.service;

import com.ibm.advocacy.model.WorkbookRequest;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkbookPaddingTest {
    @Inject WorkbookService workbooks;
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    interface Edit {void apply(XSSFWorkbook book) throws Exception;}
    byte[] fixture(Edit edit) throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new WorkbookImportTest().fixture(false)));var out=new ByteArrayOutputStream()){edit.apply(book);book.write(out);return out.toByteArray();}
    }
    WorkbookRequest request(byte[] bytes){return new WorkbookRequest(bytes,"2026-10","October inputs",List.of("Previous months"));}
    @Test void styledBlankAndWhitespacePaddingAtXfdIsIgnoredInPreludeHeaderAndData() throws Exception {
        byte[] padded=fixture(book->{var style=book.createCellStyle();style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for(String name:List.of("October inputs","Previous months"))for(var row:book.getSheet(name)){row.createCell(16383,CellType.BLANK).setCellStyle(style);row.createCell(16382).setCellValue(" \t\r\n ");}
        });
        assertEquals("October inputs",workbooks.inspect(padded).suggestedCurrentSheet());var prepared=workbooks.prepare(request(padded));assertEquals(1,prepared.current().rows.size());assertEquals("10",prepared.current().rows.getFirst().get("Views / attendance"));assertEquals(6,prepared.trailing().rows.size());assertEquals(4,prepared.trailing().rows.stream().filter(row->row.get("Current total").equals("20")).count());
        assertTrue(prepared.current().headers.size()<100);assertTrue(prepared.trailing().headers.size()<100);
        try(var book=WorkbookLimits.open(padded,"Synthetic workbook")){assertEquals(16384,book.getSheet("October inputs").getRow(0).getLastCellNum());assertEquals(CellType.BLANK,book.getSheet("October inputs").getRow(3).getCell(16383).getCellType());}
    }
    @Test void reportingSnapshotAndSavedOriginalRemainExactWithBlankPadding() throws Exception {
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
        byte[] padded=fixture(book->{for(var sheet:book)for(var row:sheet)row.createCell(16383,CellType.BLANK);}),before=padded.clone();
        var snapshot=workbooks.reportingSnapshot(padded,List.of("Previous months"));assertEquals(4,snapshot.rows.stream().filter(row->row.get("Name of activity").equals("café 日本語")&&row.get("Current total").equals("20")).count());
        var run=runs.createWorkbook(padded,"Synthetic padded workbook.xlsx","2026-10","October inputs",List.of("Previous months"));assertEquals(1,run.currentRows);assertEquals(6,run.trailingRows);assertArrayEquals(before,Files.readAllBytes(workspace.runPath(run.id,"rawdata/original.xlsx")));assertArrayEquals(before,padded);
    }
    @Test void exactlyOneHundredMeaningfulColumnsAreAcceptedAndPreserved() throws Exception {
        byte[] bytes=fixture(book->{var sheet=book.getSheet("October inputs");sheet.getRow(2).createCell(99).setCellValue("Boundary metadata");sheet.getRow(3).createCell(99).setCellValue("keep boundary");});
        try(var original=WorkbookLimits.open(bytes,"Synthetic workbook")){assertEquals(100,original.getSheet("October inputs").getRow(2).getLastCellNum());}
        var prepared=workbooks.prepare(request(bytes));assertEquals("keep boundary",prepared.current().rows.getFirst().get("Boundary metadata"));var snapshot=workbooks.reportingSnapshot(bytes,List.of("October inputs"));assertEquals("keep boundary",snapshot.rows.getFirst().get("Boundary metadata"));
    }
    @Test void realBeyondBoundaryDataIncludesHiddenZeroFalseFormulaEmptyAndExcelError() throws Exception {
        for(CellType type:List.of(CellType.NUMERIC,CellType.BOOLEAN,CellType.FORMULA,CellType.ERROR,CellType.STRING)){
            int column=type==CellType.FORMULA?16383:100;
            byte[] bytes=fixture(book->{var sheet=book.getSheet("October inputs");sheet.setColumnHidden(column,true);Cell cell=sheet.getRow(3).createCell(column);switch(type){case NUMERIC->cell.setCellValue(0);case BOOLEAN->cell.setCellValue(false);case FORMULA->{cell.setCellFormula("\"\"");book.getCreationHelper().createFormulaEvaluator().evaluateAll();}case ERROR->cell.setCellErrorValue(FormulaError.VALUE.getCode());case STRING->cell.setCellValue("meaningful");default->throw new AssertionError();}});
            var error=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(error.getMessage().contains("Sheet 'October inputs' row 4"),type+": "+error.getMessage());assertTrue(error.getMessage().contains("column "+CellReference.convertNumToColString(column)),error.getMessage());
            assertFalse(workbooks.inspect(bytes).sheets().stream().filter(sheet->sheet.name().equals("October inputs")).findFirst().orElseThrow().eligible());
        }
    }
    @Test void meaningfulPreludeAndHeaderPaddingStillRejectsOversizedSheet() throws Exception {
        for(int row:List.of(0,2)){
            byte[] bytes=fixture(book->book.getSheet("October inputs").getRow(row).createCell(100).setCellValue("unexpected metadata"));
            var error=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(error.getMessage().contains("row "+(row+1)));assertTrue(error.getMessage().contains("beyond 100 columns"));
        }
    }
    @Test void realDataWithoutHeaderInsideBoundaryStillRequiresCorrection() throws Exception {
        byte[] bytes=fixture(book->book.getSheet("October inputs").getRow(3).createCell(50).setCellValue(0));
        var error=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(error.getMessage().contains("Data without a column header in October inputs row 4"));
    }
}
