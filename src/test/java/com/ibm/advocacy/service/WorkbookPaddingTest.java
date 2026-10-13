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
    static void spread(XSSFWorkbook book,String name,int[] mapping) {
        var sheet=book.getSheet(name);var temporary=book.createSheet("Synthetic projection copy");
        for(var row:sheet){var copy=temporary.createRow(row.getRowNum());for(int c=0;c<mapping.length;c++){var cell=row.getCell(c);if(cell!=null)copy.createCell(c).copyCellFrom(cell,new CellCopyPolicy());}
            for(int c=0;c<mapping.length;c++){var cell=row.getCell(c);if(cell!=null)row.removeCell(cell);}
            for(int c=0;c<mapping.length;c++){var cell=copy.getCell(c);if(cell!=null)((org.apache.poi.xssf.usermodel.XSSFCell)row.createCell(mapping[c])).copyCellFrom(cell,new CellCopyPolicy());}
        }
        book.removeSheetAt(book.getSheetIndex(temporary));
    }
    @Test void styledBlankAndWhitespacePaddingAtXfdIsIgnoredInPreludeHeaderAndData() throws Exception {
        byte[] padded=fixture(book->{var style=book.createCellStyle();style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for(String name:List.of("October inputs","Previous months"))for(var row:book.getSheet(name)){row.createCell(16383,CellType.BLANK).setCellStyle(style);row.createCell(16382).setCellValue(" \u00a0\t\r\n\u2060\ufeff\u200b ");row.createCell(16381).setCellValue(String.valueOf((char)127));}
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
        byte[] bytes=fixture(book->{var sheet=book.getSheet("October inputs");for(int i=1;i<=91;i++){sheet.getRow(2).createCell(i*100).setCellValue("Boundary metadata "+i);sheet.getRow(3).createCell(i*100).setCellValue("keep boundary "+i);}});
        try(var original=WorkbookLimits.open(bytes,"Synthetic workbook")){assertEquals(100,WorkbookCells.populatedColumns(original.getSheet("October inputs").getRow(2)).size());}
        var prepared=workbooks.prepare(request(bytes));assertEquals("keep boundary 91",prepared.current().rows.getFirst().get("Boundary metadata 91"));var snapshot=workbooks.reportingSnapshot(bytes,List.of("October inputs"));assertEquals("keep boundary 91",snapshot.rows.getFirst().get("Boundary metadata 91"));
    }
    @Test void realBeyondBoundaryDataIncludesHiddenZeroFalseFormulaEmptyAndExcelError() throws Exception {
        for(CellType type:List.of(CellType.NUMERIC,CellType.BOOLEAN,CellType.FORMULA,CellType.ERROR,CellType.STRING)){
            int column=type==CellType.FORMULA?16383:100;
            byte[] bytes=fixture(book->{var sheet=book.getSheet("October inputs");sheet.setColumnHidden(column,true);Cell cell=sheet.getRow(3).createCell(column);switch(type){case NUMERIC->cell.setCellValue(0);case BOOLEAN->cell.setCellValue(false);case FORMULA->{cell.setCellFormula("\"\"");book.getCreationHelper().createFormulaEvaluator().evaluateAll();}case ERROR->cell.setCellErrorValue(FormulaError.VALUE.getCode());case STRING->cell.setCellValue("\u200bmeaningful\u2060"+(char)1);default->throw new AssertionError();}});
            var error=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(error.getMessage().contains("Data without a column header in October inputs row 4"),type+": "+error.getMessage());assertTrue(error.getMessage().contains("column "+CellReference.convertNumToColString(column)),error.getMessage());
            assertFalse(workbooks.inspect(bytes).sheets().stream().filter(sheet->sheet.name().equals("October inputs")).findFirst().orElseThrow().eligible());
        }
    }
    @Test void meaningfulPreludeAndHeaderPaddingStillRejectsOversizedSheet() throws Exception {
        for(int row:List.of(0,2)){
            byte[] bytes=fixture(book->{for(int c=0;c<101;c++)book.getSheet("October inputs").getRow(row).createCell(c*100).setCellValue("unexpected metadata "+c);});
            var error=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(error.getMessage().contains("row "+(row+1)));assertTrue(error.getMessage().contains("100 populated input columns"));
        }
    }
    @Test void realDataWithoutHeaderInsideBoundaryStillRequiresCorrection() throws Exception {
        byte[] bytes=fixture(book->book.getSheet("October inputs").getRow(3).createCell(50).setCellValue(0));
        var error=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(error.getMessage().contains("Data without a column header in October inputs row 4"));
    }
    @Test void rawControlOnlyCellsAreBlankButPrintableTextMixedWithControlsIsRealContent() throws Exception {
        try(var book=new XSSFWorkbook()){
            var row=book.createSheet("Control padding").createRow(0);var cell=row.createCell(16383);cell.setCellValue(String.valueOf((char)1)+(char)127+"\u2060\ufeff\u200b");assertEquals(List.of(),WorkbookCells.populatedColumns(row));
            cell.setCellValue("actual content"+(char)1+"\u2060");assertEquals(List.of(16383),WorkbookCells.populatedColumns(row));
        }
    }
    @Test void sparseInternalUnnamedDataPreservesOriginalColumnLabelAndUnionOfFieldsIsLimited() throws Exception {
        byte[] bytes=fixture(book->{var sheet=book.getSheet("October inputs");sheet.getRow(2).createCell(16382).setCellValue("Sparse last field");sheet.getRow(3).createCell(701).setCellValue("unnamed value");});
        var prepared=workbooks.prepare(request(bytes));assertEquals("unnamed value",prepared.current().rows.getFirst().get("Unnamed column 702"));assertEquals("10",prepared.current().rows.getFirst().get("Views / attendance"));
        byte[] overflow=fixture(book->{var sheet=book.getSheet("October inputs");sheet.getRow(2).createCell(16382).setCellValue("Sparse last field");var next=sheet.createRow(4);next.createCell(0).setCellValue("2026-10-11");for(int i=0;i<91;i++)sheet.getRow(i<46?3:4).createCell(20+i).setCellValue(i);});
        var failure=assertThrows(BadRequestException.class,()->workbooks.prepare(request(overflow)));assertTrue(failure.getMessage().contains("100 populated input columns"));
    }
    @Test void sparseFarRightUrlAndViewsMapBackToTheirCorrectActivityFields() throws Exception {
        byte[] bytes=fixture(book->{for(String name:List.of("October inputs","Previous months"))spread(book,name,new int[]{2,0,4,6,8,10,16382,12,701});});
        var prepared=workbooks.prepare(request(bytes));assertEquals(1,prepared.current().rows.size());var current=prepared.current().rows.getFirst();assertEquals("Daniel",current.get("Author of activity"));assertEquals("Quarkus",current.get("Tool"));assertEquals("10",current.get("Views / attendance"));assertEquals("2026-10-10",current.get("Date of activity"));
        assertEquals(4,prepared.trailing().rows.stream().filter(row->row.get("Name of activity").equals("café 日本語")&&row.get("Current total").equals("20")&&row.get("Link / URL to resource").equals("https://example.com/history")).count());
    }
}
