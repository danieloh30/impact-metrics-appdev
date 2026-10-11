package com.ibm.advocacy.service;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpactTrendsTest {
    byte[] fixture() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("AppDev Advocacy");String[] months={"September","October","November","December","January","February","March","April","May","June","July","August","September"};
            var channel=sheet.createRow(0);channel.createCell(0).setCellValue("Type of activity");var tool=sheet.createRow(33);tool.createCell(0).setCellValue("Tool");
            var blogs=sheet.createRow(1);blogs.createCell(0).setCellValue("Blog post / slide shares / .io updates");var bob=sheet.createRow(34);bob.createCell(0).setCellValue("IBM Bob");
            for(int i=0;i<months.length;i++){channel.createCell(i+1).setCellValue(months[i]);tool.createCell(i+1).setCellValue(months[i]);blogs.createCell(i+1).setCellValue(i==12?999999:100+i);bob.createCell(i+1).setCellValue(i==0?50:i==12?999999:100+i);}
            var sum=sheet.createRow(2);sum.createCell(1).setCellFormula("SUM(B2:B2)");book.createSheet("AI Advocacy").createRow(0).createCell(0).setCellValue("Other team's data must not be read");book.write(out);return out.toByteArray();
        }
    }
    @Test void repeatedSeptemberHeadingsGetDistinctYearsAndPublishedValuesAreNotReweighted() throws Exception {
        var source=new ImpactTrends().read(fixture(),"Advocacy RUN metrics 2026.xlsx",null);assertEquals("2026-09",source.endMonth());assertEquals("2025-09",source.channel().months().getFirst());assertEquals("100",source.channel().series().getFirst().values().getFirst());assertEquals(1,source.channel().series().size());assertTrue(source.warnings().getFirst().contains("100 vs 50"));
    }
    @Test void selectedMonthAlwaysReplacesItsPublishedColumnAndFutureSourceColumnsAreIgnored() throws Exception {
        var source=new ImpactTrends().read(fixture(),"Advocacy RUN metrics 2026.xlsx",null);
        var current=List.of(new MonthlyReporting.Aggregate("Blog post / slide shares / .io updates","8.25","2","10.25","20.5","Views",2));var tool=List.of(new MonthlyReporting.Aggregate("IBM Bob","8.25","2","10.25","20.5","Views",2));
        var result=ImpactTrends.build("2026-09",current,tool,source,Map.of("filename","source"));assertEquals(13,result.channel().months().size());assertEquals("2025-09",result.channel().months().getFirst());assertEquals("111",result.channel().series().getFirst().values().get(11));assertEquals("10.25",result.channel().series().getFirst().values().get(12));
        var august=ImpactTrends.build("2026-08",current,tool,source,Map.of());assertEquals("2025-08",august.channel().months().getFirst());assertNull(august.channel().series().getFirst().values().getFirst());assertFalse(august.channel().available().getFirst());assertEquals("10.25",august.channel().series().getFirst().values().getLast());assertFalse(august.channel().months().contains("2026-09"));
    }
    @Test void unavailableMonthsStayNullButBlankCellsInAnExistingPublishedMonthAreZero() throws Exception {
        byte[] bytes;try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture()));ByteArrayOutputStream out=new ByteArrayOutputStream()){book.getSheet("AppDev Advocacy").getRow(1).removeCell(book.getSheet("AppDev Advocacy").getRow(1).getCell(2));book.write(out);bytes=out.toByteArray();}
        var source=new ImpactTrends().read(bytes,"Advocacy RUN metrics 2026.xlsx",null);assertEquals("0",source.channel().series().getFirst().values().get(1));
        var missing=ImpactTrends.build("2026-09",List.of(),List.of(),null,Map.of());assertFalse(missing.channel().available().getFirst());assertEquals(1,missing.warnings().size());
    }
    @Test void invalidYearsMonthsDuplicatesAndErrorsAreRejected() throws Exception {
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->new ImpactTrends().read(fixture(),"Unknown.xlsx",null));assertThrows(jakarta.ws.rs.BadRequestException.class,()->new ImpactTrends().read(fixture(),"2025 2026.xlsx",null));assertThrows(jakarta.ws.rs.BadRequestException.class,()->new ImpactTrends().read(fixture(),"2026.xlsx","2026-08"));
        byte[] bytes;try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(fixture()));ByteArrayOutputStream out=new ByteArrayOutputStream()){book.getSheet("AppDev Advocacy").getRow(1).getCell(1).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.REF.getCode());book.write(out);bytes=out.toByteArray();}final byte[] invalid=bytes;assertThrows(jakarta.ws.rs.BadRequestException.class,()->new ImpactTrends().read(invalid,"2026.xlsx",null));
    }
    @Test void aFullyExcludedSelectedMonthIsUnavailableNotAFalseZero() throws Exception {
        var history=new ImpactTrends().read(fixture(),"Advocacy RUN metrics 2026.xlsx",null);var result=ImpactTrends.build("2026-09",List.of(),List.of(),history,Map.of(),false);
        assertFalse(result.channel().available().getLast());assertNull(result.channel().series().getFirst().values().getLast());assertTrue(result.channel().available().getFirst());
    }
    @Test void nativeWorkbookExportsTwoStackedAreaChartsAndBobIsOptional() throws Exception {
        var fixture=new MonthlyReportingTest();var row=fixture.row("video","2026-09-10","IBM Bob","Recorded video","9999");var report=MonthlyReporting.build(fixture.run(),fixture.data(row),new com.ibm.advocacy.model.Data(),new com.ibm.advocacy.model.Data(),"combined",fixture.context(fixture.data(fixture.snapshot(row,"166.5","0"))));
        for(boolean bob:List.of(false,true))try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(new ReportingWorkbook().write(report,bob)))) {
            int charts=0;for(var sheet:book)if(((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getDrawingPatriarch()!=null)charts+=((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getDrawingPatriarch().getCharts().size();assertEquals(bob?3:2,charts);
            for(String name:List.of("Channel history","Tool history")){var chart=book.getSheet(name).getDrawingPatriarch().getCharts().getFirst();assertEquals("stacked",chart.getCTChart().getPlotArea().getAreaChartArray(0).getGrouping().getVal().toString());assertEquals(13,chart.getChartSeries().getFirst().getSeries(0).getCategoryData().getPointCount());assertNull(book.getSheet(name).getRow(1).getCell(1));assertEquals(83.25,book.getSheet(name).getRow(13).getCell(1).getNumericCellValue());}
        }
    }
}
