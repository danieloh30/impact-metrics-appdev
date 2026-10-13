package com.ibm.advocacy.service;

import com.ibm.advocacy.model.WorkbookRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BlogPaddingTest {
    @Inject WorkbookService workbooks;
    interface Edit {void apply(XSSFWorkbook book) throws Exception;}
    byte[] fixture(Edit edit) throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new BlogWorkbookTest().fixture()));var out=new ByteArrayOutputStream()){
            var style=book.createCellStyle();style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for(String name:List.of("Markus blog data","Elder blog data")){
                var sheet=book.getSheet(name);sheet.shiftRows(0,sheet.getLastRowNum(),2);sheet.createRow(0).createCell(0).setCellValue("Blog source prelude");sheet.createRow(1);
                for(var row:sheet){row.createCell(16383,CellType.BLANK).setCellStyle(style);row.createCell(16382).setCellValue(" \u00a0\t\r\n\u2060\ufeff\u200b ");row.createCell(16381).setCellValue(String.valueOf((char)127));}
            }
            edit.apply(book);book.write(out);return out.toByteArray();
        }
    }
    WorkbookRequest request(byte[] bytes){return new WorkbookRequest(bytes,"2026-10","Current month inputs",List.of("Previous months inputs"));}
    byte[] repeated(int markusCopies,int elderCopies,Edit edit) throws Exception {
        return fixture(book->{for(String name:List.of("Markus blog data","Elder blog data")){
            int copies=name.startsWith("Markus")?markusCopies:elderCopies;var sheet=book.getSheet(name);var header=sheet.getRow(2);
            List<Integer> fields=WorkbookCells.populatedColumnsUnchecked(header).stream().filter(c->c<15).toList();
            for(var row:sheet)if(row.getRowNum()>=2)for(int copy=1;copy<copies;copy++)for(int c:fields){var cell=row.getCell(c);if(cell!=null)((org.apache.poi.xssf.usermodel.XSSFCell)row.createCell(copy*16+c)).copyCellFrom(cell,new CellCopyPolicy());}
        }book.getCreationHelper().createFormulaEvaluator().evaluateAll();edit.apply(book);});
    }
    @Test void bothPaddedBlogSchemasPrepareOctoberAndPreserveDatePrecisionMetricsAndUndatedCount() throws Exception {
        byte[] bytes=fixture(book->{}),before=bytes.clone();var inspection=workbooks.inspect(bytes);
        for(String name:List.of("Markus blog data","Elder blog data"))assertTrue(inspection.sheets().stream().filter(sheet->sheet.name().equals(name)).findFirst().orElseThrow().eligible());
        var prepared=workbooks.prepare(request(bytes));assertEquals(3,prepared.current().rows.size());assertEquals(1,prepared.trailing().rows.size());assertEquals(Map.of("Markus blog data",1,"Elder blog data",1),prepared.blogRows());assertEquals(1,prepared.blogSkippedRows().get("Elder blog data"));
        var markus=prepared.current().rows.stream().filter(row->row.get("Source sheet").equals("Markus blog data")).findFirst().orElseThrow();assertEquals("24",markus.get("Views / attendance"));assertEquals("2026-10-01",markus.get("Date of activity"));assertEquals("4",markus.get("Source row"));assertEquals("café 日本語",markus.get("Name of activity"));assertEquals("Markus; Alex",markus.get("Author of activity"));
        var elder=prepared.current().rows.stream().filter(row->row.get("Source sheet").equals("Elder blog data")).findFirst().orElseThrow();assertEquals("31",elder.get("Views / attendance"));assertEquals("2026-10-01",elder.get("Date of activity"));assertEquals("Month only",elder.get("Source date precision"));assertEquals("No",elder.get("Trailing 90-day"));
        assertFalse(prepared.current().rows.stream().anyMatch(row->row.get("Link / URL to resource").equals("/undated-post")));assertArrayEquals(before,bytes);
    }
    @Test void paddedElderSeptemberOnlyIsValidatedButNotAppendedToOctober() throws Exception {
        byte[] bytes=fixture(book->book.getSheet("Elder blog data").getRow(3).getCell(0).setCellValue("September 2026"));
        var elder=workbooks.inspect(bytes).sheets().stream().filter(sheet->sheet.name().equals("Elder blog data")).findFirst().orElseThrow();assertTrue(elder.eligible());assertEquals(Map.of("2026-09",2),elder.monthCounts());assertEquals(1,elder.undatedRows());
        var prepared=workbooks.prepare(request(bytes));assertEquals(0,prepared.blogRows().get("Elder blog data"));assertEquals(2,prepared.current().rows.size());assertFalse(prepared.current().rows.stream().anyMatch(row->row.get("Source sheet").equals("Elder blog data")));
        // Nonselected months still undergo source validation rather than hiding bad data.
        byte[] invalid=fixture(book->{var sheet=book.getSheet("Elder blog data");sheet.getRow(3).getCell(0).setCellValue("September 2026");sheet.getRow(3).createCell(100).setCellValue(0);});
        var failure=assertThrows(BadRequestException.class,()->workbooks.prepare(request(invalid)));assertTrue(failure.getMessage().contains("Elder blog data"));
    }
    @Test void meaningfulBeyondOneHundredBlogCellsAreRejectedEvenWhenHiddenOrFormulaDisplaysBlank() throws Exception {
        for(String name:List.of("Markus blog data","Elder blog data"))for(CellType type:List.of(CellType.NUMERIC,CellType.FORMULA,CellType.ERROR,CellType.STRING)){
            byte[] bytes=fixture(book->{var sheet=book.getSheet(name);sheet.setColumnHidden(100,true);var cell=sheet.getRow(3).createCell(100);switch(type){case NUMERIC->cell.setCellValue(0);case FORMULA->{cell.setCellFormula("\"\"");book.getCreationHelper().createFormulaEvaluator().evaluateAll();}case ERROR->cell.setCellErrorValue(FormulaError.VALUE.getCode());case STRING->cell.setCellValue("\u200bactual extra data\u2060"+(char)1);default->throw new AssertionError();}});
            var failure=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(failure.getMessage().contains("Data without a column header in "+name+" row 4"),failure.getMessage());
        }
    }
    @Test void meaningfulOversizedPreludeAndHeaderRemainRejectedForBothBlogAdapters() throws Exception {
        for(String name:List.of("Markus blog data","Elder blog data"))for(int row:List.of(0,2)){
            byte[] bytes=fixture(book->{for(int c=0;c<101;c++)book.getSheet(name).getRow(row).createCell(c*100).setCellValue("unexpected metadata "+c);});
            var failure=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(failure.getMessage().contains("Sheet '"+name+"' row "+(row+1)),failure.getMessage());
        }
    }
    @Test void actualUnheadedDataInsideBoundaryStillRequiresCorrectionDespiteXfdBlankPadding() throws Exception {
        for(String name:List.of("Markus blog data","Elder blog data")){
            byte[] bytes=fixture(book->book.getSheet(name).getRow(3).createCell(50).setCellValue(0));
            var failure=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(failure.getMessage().contains("Data without a column header in "+name+" row 4"),failure.getMessage());
        }
    }
    @Test void bothSparseBlogAdaptersUseFarRightUrlAndMappedMonthAuthorToolAndFormulaViews() throws Exception {
        byte[] bytes=fixture(book->{for(String name:List.of("Markus blog data","Elder blog data"))WorkbookPaddingTest.spread(book,name,new int[]{2,0,4,6,8,10,12,14,16,18,20,22,701,26,16382});book.getCreationHelper().createFormulaEvaluator().evaluateAll();}),before=bytes.clone();
        var inspection=workbooks.inspect(bytes);for(String name:List.of("Markus blog data","Elder blog data"))assertTrue(inspection.sheets().stream().filter(sheet->sheet.name().equals(name)).findFirst().orElseThrow().eligible());
        var prepared=workbooks.prepare(request(bytes));assertEquals(Map.of("Markus blog data",1,"Elder blog data",1),prepared.blogRows());assertEquals(1,prepared.blogSkippedRows().get("Elder blog data"));
        var markus=prepared.current().rows.stream().filter(row->row.get("Source sheet").equals("Markus blog data")).findFirst().orElseThrow();assertEquals("24",markus.get("Views / attendance"));assertEquals("https://example.com/markus",markus.get("Link / URL to resource"));assertEquals("Markus; Alex",markus.get("Author of activity"));assertEquals("IBM Runtimes",markus.get("Tool"));assertEquals("2026-10-01",markus.get("Date of activity"));
        var elder=prepared.current().rows.stream().filter(row->row.get("Source sheet").equals("Elder blog data")).findFirst().orElseThrow();assertEquals("31",elder.get("Views / attendance"));assertEquals("https://example.com/elder",elder.get("Link / URL to resource"));assertEquals("Elder Moraes",elder.get("Author of activity"));assertEquals("Month only",elder.get("Source date precision"));assertEquals("2026-10-01",elder.get("Date of activity"));assertArrayEquals(before,bytes);
    }
    @Test void twentyExactHorizontalCopiesCollapseWithoutDuplicatingActivitiesOrViews() throws Exception {
        byte[] bytes=repeated(20,20,book->{}),before=bytes.clone();var prepared=workbooks.prepare(request(bytes));assertEquals(3,prepared.current().rows.size());assertEquals(Map.of("Markus blog data",1,"Elder blog data",1),prepared.blogRows());
        var markus=prepared.current().rows.stream().filter(row->row.get("Source sheet").equals("Markus blog data")).findFirst().orElseThrow();assertEquals("24",markus.get("Views / attendance"));assertEquals("https://example.com/markus",markus.get("Link / URL to resource"));assertEquals("247",markus.get("Source duplicate columns"));
        var elder=prepared.current().rows.stream().filter(row->row.get("Source sheet").equals("Elder blog data")).findFirst().orElseThrow();assertEquals("31",elder.get("Views / attendance"));assertEquals("133",elder.get("Source duplicate columns"));assertEquals("Month only",elder.get("Source date precision"));assertEquals(1,prepared.blogSkippedRows().get("Elder blog data"));assertArrayEquals(before,bytes);
    }
    @Test void elderOneThousandTwentyFourCopiesAcrossExcelWidthCollapseWithExactProvenance() throws Exception {
        byte[] bytes=repeated(1,1024,book->{}),before=bytes.clone();var inspected=workbooks.inspect(bytes);assertTrue(inspected.sheets().stream().filter(sheet->sheet.name().equals("Elder blog data")).findFirst().orElseThrow().eligible());
        var prepared=workbooks.prepare(request(bytes));assertEquals(1,prepared.blogRows().get("Elder blog data"));assertEquals(3,prepared.current().rows.size());var elder=prepared.current().rows.getLast();assertEquals("7161",elder.get("Source duplicate columns"));assertEquals("31",elder.get("Views / attendance"));assertEquals("https://example.com/elder",elder.get("Link / URL to resource"));assertEquals("2026-10-01",elder.get("Date of activity"));assertArrayEquals(before,bytes);
    }
    @Test void differingRepeatedMetricsUrlsTypesExpressionsAndCachedValuesNeverChooseOneCopy() throws Exception {
        for(String kind:List.of("views","url","type","formula","cached","empty")){
            boolean markus=kind.equals("formula")||kind.equals("cached");String name=markus?"Markus blog data":"Elder blog data";
            byte[] bytes=repeated(2,2,book->{var row=book.getSheet(name).getRow(3);switch(kind){case "views"->row.getCell(28).setCellValue("32");case "url"->row.getCell(30).setCellValue("https://example.com/conflicting");case "type"->row.getCell(28).setCellValue(31);case "formula"->{row.getCell(28).setCellFormula("2*12");book.getCreationHelper().createFormulaEvaluator().evaluateAll();}case "cached"->row.getCell(28).getCTCell().setV("25");case "empty"->row.getCell(23).setCellValue("Blog post / slide shares / .io updates");default->throw new AssertionError();}});
            var failure=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(failure.getMessage().contains("Conflicting repeated blog column"),failure.getMessage());assertTrue(failure.getMessage().contains(name+" row 4"));assertTrue(failure.getMessage().contains("columns "));
        }
    }
    @Test void repeatedDatePrecisionAndDifferentNumericDatesWithSameMonthDisplayAreRejected() throws Exception {
        byte[] precision=repeated(2,2,book->book.getSheet("Elder blog data").getRow(2).getCell(16).setCellValue("Date"));var collision=assertThrows(BadRequestException.class,()->workbooks.prepare(request(precision)));assertTrue(collision.getMessage().contains("different date precision"));
        byte[] dates=repeated(2,2,book->{var row=book.getSheet("Elder blog data").getRow(3);var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("mmmm yyyy"));row.getCell(0).setCellValue(java.time.LocalDate.of(2026,10,1));row.getCell(16).setCellValue(java.time.LocalDate.of(2026,10,15));row.getCell(0).setCellStyle(style);row.getCell(16).setCellStyle(style);});
        var conflict=assertThrows(BadRequestException.class,()->workbooks.prepare(request(dates)));assertTrue(conflict.getMessage().contains("Conflicting repeated blog column 'Date of activity'"));
    }
    @Test void overOneHundredUniqueLogicalBlogFieldsRemainRejectedAfterDuplicateCollapsing() throws Exception {
        byte[] bytes=repeated(1,1,book->{var header=book.getSheet("Elder blog data").getRow(2);for(int i=0;i<94;i++)header.createCell(200+i).setCellValue("Unique extra field "+i);});
        var failure=assertThrows(BadRequestException.class,()->workbooks.prepare(request(bytes)));assertTrue(failure.getMessage().contains("100 populated input fields"),failure.getMessage());
    }
}
