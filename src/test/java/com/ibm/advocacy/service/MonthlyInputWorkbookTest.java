package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.model.Row;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.ss.SpreadsheetVersion;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

class MonthlyInputWorkbookTest {
    MonthlyInputWorkbook writer=new MonthlyInputWorkbook();
    static final String[] HEAD={"Date of activity","Author of activity (required)","Tool (required)","Name of activity","Type of activity","Link / URL to public resource","Current total","Previous total",VIEWS,"Trailing 90-day","Custom formula"};
    byte[] fixture(boolean duplicate) throws Exception {try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        var sheet=book.createSheet("September inputs");var head=sheet.createRow(0);for(int i=0;i<HEAD.length;i++)head.createCell(i).setCellValue(HEAD[i]);String[] values={"9/10/2026","A","X","Existing","Recorded video","https://example.com/existing","100","60","40","Yes"};for(int r=1;r<=(duplicate?3:2);r++){var row=sheet.createRow(r);for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);if(r==2){row.getCell(3).setCellValue("Unmatched");row.getCell(0).setCellValue("2026-09-11");}row.createCell(10).setCellFormula("1+2");}
        sheet.createTable(new AreaReference(new CellReference(0,0),new CellReference(sheet.getLastRowNum(),10),SpreadsheetVersion.EXCEL2007));var model=book.createSheet("DA metric model 2026");model.createRow(0).createCell(0).setCellValue("Unchanged model");book.createName().setNameName("ImportantRange");book.getName("ImportantRange").setRefersToFormula("'DA metric model 2026'!$A$1");book.write(out);return out.toByteArray();}}
    Row row(String id,String name,String total){return new Row(id,Map.of("Date","2026-09-10","Author","A","Tool","X","Name of activity",name,"Type of activity","Recorded video",URL,"https://example.com/"+name.toLowerCase(),"Current total",total,"Previous total","100",VIEWS,total,"Starting baseline","100"));}
    Data data(Row... rows){return Data.output(new Data(),List.of(rows));}
    @Test void upsertNormalizesHeadersAndDatesAndKeepsUnmatchedDataFormulasTablesAndExactCounts() throws Exception {
        byte[] original=fixture(false),unchangedOriginal=original.clone();var result=writer.merge(original,data(row("1","Existing","200"),row("2","New","166.5")),"2026-09","CURRENT_INPUTS","run");assertFalse(result.created());assertEquals(1,result.updated());assertEquals(1,result.appended());
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))){var sheet=book.getSheet("September inputs");assertEquals(3,sheet.getLastRowNum());assertEquals(200,sheet.getRow(1).getCell(6).getNumericCellValue());assertEquals("Unmatched",sheet.getRow(2).getCell(3).getStringCellValue());assertEquals("100",sheet.getRow(2).getCell(6).getStringCellValue());assertEquals("1+2",sheet.getRow(1).getCell(10).getCellFormula());assertEquals(166.5,sheet.getRow(3).getCell(6).getNumericCellValue());assertEquals(3,sheet.getTables().getFirst().getArea().getLastCell().getRow());assertEquals("Unchanged model",book.getSheet("DA metric model 2026").getRow(0).getCell(0).getStringCellValue());assertNotNull(book.getName("ImportantRange"));}
        var repeated=writer.merge(result.bytes(),data(row("1","Existing","200"),row("2","New","166.5")),"2026-09","CURRENT_INPUTS","run");assertEquals(0,repeated.appended());assertEquals(0,repeated.updated());assertEquals(2,repeated.unchanged());assertArrayEquals(unchangedOriginal,original);
    }
    @Test void carryOnlyRowsNeverOverwriteAnExistingNewerTotal() throws Exception {Row carry=row("1","Existing","999");carry.cells.put("Snapshot stage","BASELINE_CARRY");var result=writer.merge(fixture(false),data(carry),"2026-09","CURRENT_INPUTS","run");assertEquals(1,result.unchanged());try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))){assertEquals("100",book.getSheet("September inputs").getRow(1).getCell(6).getStringCellValue());}}
    @Test void duplicateExistingOrIncomingAttributionsBlockBeforeAnyWrite() throws Exception {var conflict=assertThrows(BadRequestException.class,()->writer.merge(fixture(true),data(row("1","Existing","200")),"2026-09","CURRENT_INPUTS","run"));assertTrue(conflict.getMessage().contains("Sheet: September inputs; matching Excel rows: 2, 4"));assertTrue(conflict.getMessage().contains("Author: A; Tool: X"));assertTrue(conflict.getMessage().contains("Different author/tool splits are valid"));assertTrue(conflict.getMessage().contains("No workbook changes were published"));assertThrows(BadRequestException.class,()->writer.merge(fixture(false),data(row("1","Existing","200"),row("2","Existing","200")),"2026-09","CURRENT_INPUTS","run"));}
    @Test void combinedExistingAuthorRowsCannotSilentlyCreateDuplicateAttributedShares() throws Exception {byte[] original;try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture(false)));ByteArrayOutputStream out=new ByteArrayOutputStream()){book.getSheet("September inputs").getRow(1).getCell(1).setCellValue("A;B");book.write(out);original=out.toByteArray();}var error=assertThrows(BadRequestException.class,()->writer.merge(original,data(row("1","Existing","200")),"2026-09","CURRENT_INPUTS","run"));assertTrue(error.getMessage().contains("combines authors or tools"));try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(original))){assertEquals(2,book.getSheet("September inputs").getLastRowNum());}}
    @Test void newMonthlyTabIsYearQualifiedAndPartialSnapshotCannotSupplyALaterMonthBaseline() throws Exception {var partial=writer.merge(fixture(false),data(row("1","Existing","200")),"2026-10","CURRENT_INPUTS","run");assertTrue(partial.created());assertEquals("October 2026 inputs",partial.sheet());WorkbookService reader=new WorkbookService();assertEquals(List.of("October 2026 inputs"),reader.historySheets(partial.bytes(),"2026-10"));assertThrows(BadRequestException.class,()->reader.historySheets(partial.bytes(),"2026-11"));var complete=writer.merge(partial.bytes(),data(row("1","Existing","200")),"2026-10","COMPLETED","run");assertEquals(List.of("October 2026 inputs"),reader.historySheets(complete.bytes(),"2026-11"));}
    @Test void sameMonthRerunUsesStartingBaselineButFollowingMonthUsesLatestCumulativeTotal() throws Exception {
        byte[] complete=writer.merge(fixture(false),data(row("1","Existing","200")),"2026-10","COMPLETED","run").bytes();byte[] current;
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){var sheet=book.createSheet("Current month inputs");String[] headers={"Date","Author","Tool","Type of activity","Name of activity",URL,VIEWS};var head=sheet.createRow(0);for(int i=0;i<headers.length;i++)head.createCell(i).setCellValue(headers[i]);for(int r=1;r<=2;r++){var row=sheet.createRow(r);String[] values={r==1?"2026-10-01":"2026-11-01","A","X","Recorded video","Current","https://example.com/current","300"};for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}book.write(out);current=out.toByteArray();}
        WorkbookService reader=new WorkbookService();reader.blogs=new BlogSheetReader();
        var rerun=reader.prepare(new WorkbookRequest(current,"2026-10","Current month inputs",List.of("October 2026 inputs"),complete),java.time.LocalDate.of(2026,10,31));assertEquals("100",rerun.trailing().rows.getFirst().get("Current total"));assertEquals("200",rerun.trailing().rows.getFirst().get("Snapshot cumulative total"));var next=reader.prepare(new WorkbookRequest(current,"2026-11","Current month inputs",List.of("October 2026 inputs"),complete),java.time.LocalDate.of(2026,11,1));assertEquals("200",next.trailing().rows.getFirst().get("Current total"));
    }
    @Test void provisionalOctoberUpsertsExactTotalsBesideSeptemberButCannotBecomeNovembersBaseline() throws Exception {
        byte[] original=fixture(false);var interim=writer.merge(original,data(row("1","Existing","166.5")),"2026-10","PROVISIONAL","oct-13");
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(interim.bytes()))){
            assertEquals(book.getSheetIndex("September inputs")+1,book.getSheetIndex("October 2026 inputs"));assertTrue(book.getSheetIndex("October 2026 inputs")<book.getSheetIndex("DA metric model 2026"));
            var sheet=book.getSheet("October 2026 inputs");assertEquals(166.5,sheet.getRow(1).getCell(MonthlyInputWorkbook.columns(sheet.getRow(0),new org.apache.poi.ss.usermodel.DataFormatter()).get("Current total")).getNumericCellValue());
        }
        WorkbookService reader=new WorkbookService();assertEquals(List.of("October 2026 inputs"),reader.historySheets(interim.bytes(),"2026-10"));
        var blocked=assertThrows(BadRequestException.class,()->reader.historySheets(interim.bytes(),"2026-11"));assertTrue(blocked.getMessage().contains("provisional"));
        var later=writer.merge(interim.bytes(),data(row("1","Existing","200")),"2026-10","PROVISIONAL","oct-20");assertEquals(1,later.updated());assertEquals(0,later.appended());assertFalse(later.created());
        assertThrows(BadRequestException.class,()->reader.historySheets(later.bytes(),"2026-11"));
        var finalSnapshot=writer.merge(later.bytes(),data(row("1","Existing","250")),"2026-10","COMPLETED","oct-31");assertEquals(List.of("October 2026 inputs"),reader.historySheets(finalSnapshot.bytes(),"2026-11"));
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(finalSnapshot.bytes()))){var sheet=book.getSheet("October 2026 inputs");assertEquals(250,sheet.getRow(1).getCell(MonthlyInputWorkbook.columns(sheet.getRow(0),new org.apache.poi.ss.usermodel.DataFormatter()).get("Current total")).getNumericCellValue());assertEquals(1,sheet.getLastRowNum());}
    }
    @Test void monthTabFallsBackToEndWhenPriorMonthDoesNotExist() throws Exception {
        var result=writer.merge(fixture(false),data(row("1","Existing","200")),"2026-11","PROVISIONAL","run");
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))){assertTrue(book.getSheetIndex("November 2026 inputs")>book.getSheetIndex("DA metric model 2026"));}
    }
    @Test void olderInterimPublicationAndInterimAfterFinalCannotReplaceNewerSnapshot() throws Exception {
        Row latest=row("1","Existing","200");latest.cells.put("Snapshot as of","2026-10-20");
        byte[] interim=writer.merge(fixture(false),data(latest),"2026-10","PROVISIONAL","oct-20").bytes();
        Row older=row("1","Existing","166.5");older.cells.put("Snapshot as of","2026-10-13");
        var stale=assertThrows(BadRequestException.class,()->writer.merge(interim,data(older),"2026-10","PROVISIONAL","oct-13"));assertTrue(stale.getMessage().contains("newer snapshot"));
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(interim))){assertEquals("2026-10-20",book.getSheet(MonthlyInputWorkbook.META).getRow(1).getCell(4).getStringCellValue());}
        latest.cells.put("Snapshot as of","2026-10-31");byte[] completed=writer.merge(interim,data(latest),"2026-10","COMPLETED","oct-31").bytes();
        var downgraded=assertThrows(BadRequestException.class,()->writer.merge(completed,data(older),"2026-10","PROVISIONAL","oct-13"));assertTrue(downgraded.getMessage().contains("final snapshot"));
    }
    @Test void ownedMetricFormulasBecomeReviewedValuesWhileCustomFormulasRemain() throws Exception {
        byte[] original;try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture(false)));ByteArrayOutputStream out=new ByteArrayOutputStream()){var row=book.getSheet("September inputs").getRow(1);row.getCell(6).setCellFormula("753/4");row.getCell(8).setCellFormula("753/4");book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(out);original=out.toByteArray();}
        var result=writer.merge(original,data(row("1","Existing","188.25")),"2026-09","COMPLETED","run");try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))){var row=book.getSheet("September inputs").getRow(1);assertEquals(org.apache.poi.ss.usermodel.CellType.NUMERIC,row.getCell(6).getCellType());assertEquals(org.apache.poi.ss.usermodel.CellType.NUMERIC,row.getCell(8).getCellType());assertEquals(188.25,row.getCell(8).getNumericCellValue());assertEquals("1+2",row.getCell(10).getCellFormula());book.getCreationHelper().createFormulaEvaluator().evaluateAll();assertEquals(188.25,row.getCell(8).getNumericCellValue());}
        var repeat=writer.merge(result.bytes(),data(row("1","Existing","188.25")),"2026-09","COMPLETED","run");assertEquals(0,repeat.updated());
    }

    @Test void metadataPastBlankPaddingCannotExpandTheExcelTableHorizontally() throws Exception {
        byte[] original;try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture(false)));ByteArrayOutputStream out=new ByteArrayOutputStream()){book.getSheet("September inputs").getRow(0).createCell(60).setCellValue("Existing distant metadata");book.write(out);original=out.toByteArray();}
        var result=writer.merge(original,data(row("1","Existing","200"),row("2","New","300")),"2026-09","COMPLETED","run");try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))){var table=book.getSheet("September inputs").getTables().getFirst();assertEquals(10,table.getArea().getLastCell().getCol());assertEquals(3,table.getArea().getLastCell().getRow());assertTrue(Arrays.stream(table.getCTTable().getTableColumns().getTableColumnArray()).allMatch(column->!column.getName().isBlank()));}
    }
    @Test void malformedBlankTableHeadersStopBeforePublication() throws Exception {
        byte[] original;try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture(false)));ByteArrayOutputStream out=new ByteArrayOutputStream()){var sheet=book.getSheet("September inputs");sheet.getTables().getFirst().setArea(new AreaReference(new CellReference(0,0),new CellReference(2,13),SpreadsheetVersion.EXCEL2007));for(int i=11;i<14;i++)sheet.getTables().getFirst().getCTTable().getTableColumns().getTableColumnArray(i).setName("");book.write(out);original=out.toByteArray();}
        var error=assertThrows(BadRequestException.class,()->writer.merge(original,data(row("1","Existing","200")),"2026-09","COMPLETED","run"));assertTrue(error.getMessage().contains("blank or duplicate column names"));
    }

}
