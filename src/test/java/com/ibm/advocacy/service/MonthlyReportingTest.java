package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyReportingTest {
    Run run(){Run r=new Run();r.status="COMPLETED";r.workbook.put("month","2026-09");return r;}
    Row row(String id,String date,String tool,String type,String views){return new Row(id,Map.of("Date",date,"Author","Daniel Oh","Tool",tool,"Type of activity",type,"Name of activity",id,URL,"https://example.com/"+id,VIEWS,views));}
    Data data(Row... rows){return Data.output(new Data(),List.of(rows));}
    MonthlyReporting.Context context(Data snapshot){return new MonthlyReporting.Context(Map.of("recorded video",new MonthlyReporting.Rule("Views","0.5","Yes"),"customer workshop",new MonthlyReporting.Rule("Attendance hours","10","No"),"blog post / slide shares / .io updates",new MonthlyReporting.Rule("Views","0.5","Yes")),Map.of("daniel oh","AMER"),snapshot,true);}
    Row snapshot(Row row,String total,String previous){Row s=row.copy();s.cells.put("Current total",total);s.cells.put("Previous total",previous);return s;}
    @Test void currentImpactUsesThe2026WeightAndExactMonthEndSnapshotNotLaterLiveCount(){
        Row live=row("video","2026-09-10","IBM Bob","Recorded video","9999");
        var report=MonthlyReporting.build(run(),data(live),new Data(),new Data(),"combined",context(data(snapshot(live,"166.5","0"))));
        assertEquals("83.25",report.impact());assertEquals("166.5",report.bob().getFirst().metrics());
        Row actual=report.details().rows.getFirst();assertEquals("AMER",actual.get("Geography"));assertEquals("0.5",actual.get("Weight"));assertEquals("Yes",actual.get("Trailing 90-day"));assertEquals("166.5",actual.get("Current total"));assertEquals("167",Calculations.reported(report.details(),false).rows.getFirst().get(VIEWS));assertEquals("9999",live.get(VIEWS));
    }
    @Test void trailingUsesSnapshotPreviousTotalOnceAndNeverReusesLiveGrowth(){
        Row live=row("old","2026-07-10","IBM Bob","Recorded video","10000");live.cells.put("Previous total","200");live.cells.put("Metric basis","Trailing delta");
        var report=MonthlyReporting.build(run(),new Data(),data(live),new Data(),"trailing",context(data(snapshot(live,"166.5","150"))));
        assertEquals("8.25",report.impact());assertEquals("16.5",report.details().rows.getFirst().get("Exact reporting metric"));assertEquals("150",report.details().rows.getFirst().get("Previous total"));
    }
    @Test void oneTimeBlogSheetsHaveNoTotalsOrTrailingFlagAndAreWeightedOnce(){
        for(String sheet:List.of("Markus blog data","Elder blog data")) {
            Row blog=row(sheet+":2/0","2026-09-01","IBM Bob","Blog post / slide shares / .io updates","105.5");blog.cells.put("Current total","999");
            var report=MonthlyReporting.build(run(),data(blog),new Data(),new Data(),"current",context(new Data()));
            assertEquals("52.75",report.impact());Row actual=report.details().rows.getFirst();assertEquals("No",actual.get("Trailing 90-day"));assertEquals("",actual.get("Current total"));assertEquals("",actual.get("Previous total"));
        }
    }
    @Test void workshopsUseAllocatedAttendanceHoursWithoutSalesExceptionOrSecondDurationMultiply(){
        Row workshop=row("workshop","2026-09-01","IBM Bob","Customer workshop","25");
        var report=MonthlyReporting.build(run(),data(workshop),new Data(),new Data(),"current",context(new Data()));
        assertEquals("250",report.impact());assertEquals("Attendance hours",report.activity().getFirst().unit());
    }
    @Test void overlapAndDuplicatesDoNotDoubleCountButDifferentToolsRemainSeparate(){
        Row first=row("video","2026-09-10","IBM Bob","Recorded video","99");Row other=first.copy();other.id="other";other.cells.put("Tool","IBM Runtimes");
        Row history=first.copy();history.id="historical";history.cells.put("Metric basis","Trailing delta");
        var report=MonthlyReporting.build(run(),data(first,first.copy(),other),data(history),new Data(),"combined",context(data(snapshot(first,"50","0"),snapshot(other,"50","0"))));
        assertEquals(2,report.included());assertEquals(2,report.excluded());assertEquals("50",report.impact());assertEquals(2,report.tool().size());assertEquals("50",report.bob().getFirst().metrics());
        var standalone=MonthlyReporting.build(run(),new Data(),data(history),new Data(),"trailing",context(data(snapshot(first,"50","20"))));assertEquals("15",standalone.impact());
    }
    @Test void missingOrAmbiguousSnapshotsAndFollowupsStayExplicitlyExcluded(){
        Row row=row("video","2026-09-10","IBM Bob","Recorded video","9999");
        var missing=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",context(new Data()));assertEquals(0,missing.included());assertTrue(missing.details().rows.getFirst().get("Reporting reason").contains("Month-end total unavailable"));
        Row snap=snapshot(row,"50","0");var ambiguous=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",context(data(snap,snap.copy())));assertEquals(0,ambiguous.included());assertTrue(ambiguous.details().rows.getFirst().get("Reporting reason").contains("Ambiguous"));
        var follow=MonthlyReporting.build(run(),data(row),new Data(),data(row),"current",context(data(snap)));assertEquals(0,follow.included());assertEquals("Unresolved follow-up activity",follow.details().rows.getFirst().get("Reporting reason"));
    }
    @Test void onlyAnExplicitMatchingMonthEndOverrideReplacesMissingSnapshot(){
        Run run=run();Row row=row("video","2026-09-10","IBM Bob","Recorded video","9999");
        run.monthEndValues.put("current:video",new MonthEndValue("100","2026-10-01","later", "2026-10-11T12:00:00Z"));assertEquals(0,MonthlyReporting.build(run,data(row),new Data(),new Data(),"current",context(new Data())).included());
        run.monthEndValues.put("current:video",new MonthEndValue("100","2026-09-30","dated analytics", "2026-10-11T12:00:00Z"));var report=MonthlyReporting.build(run,data(row),new Data(),new Data(),"current",context(new Data()));assertEquals("50",report.impact());assertEquals("2026-10-11T12:00:00Z",report.details().rows.getFirst().get("Recorded at"));
    }
    @Test void priorMonthSnapshotDoesNotMasqueradeAsSelectedMonthAndNegativeDeltaIsExcluded(){
        Row row=row("video","2026-09-10","IBM Bob","Recorded video","9999");var original=context(data(snapshot(row,"100","0")));
        var stale=new MonthlyReporting.Context(original.rules(),original.geography(),original.snapshot(),false);
        assertEquals(0,MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",stale).included());
        var negative=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",context(data(snapshot(row,"5","10"))));assertEquals(0,negative.included());assertTrue(negative.details().rows.getFirst().get("Reporting reason").contains("Negative"));
    }
    @Test void anOpenReportingMonthCannotProduceFinalMonthEndTotals(){
        Run run=run();run.workbook.put("month",java.time.YearMonth.now().plusMonths(1).toString());
        Row workshop=row("workshop","2026-09-01","IBM Bob","Customer workshop","25");
        var report=MonthlyReporting.build(run,data(workshop),new Data(),new Data(),"current",context(new Data()));
        assertEquals(0,report.included());assertTrue(report.details().rows.getFirst().get("Reporting reason").contains("has not closed"));
    }
    @Test void nativeExcelPivotsUseSumAndChartsReconcileWithTypedExactDetail() throws Exception {
        Row row=row("video","2026-09-10","IBM Bob","Recorded video","9999");var report=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"combined",context(data(snapshot(row,"166.5","0"))));
        byte[] bytes=new ReportingWorkbook().write(report);
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(83.25,book.getSheet("Report").getRow(5).getCell(1).getNumericCellValue());
            for(String name:List.of("Activity pivot","Tool pivot","IBM Bob pivot")) {
                var pivot=book.getSheet(name).getPivotTables().getFirst();assertEquals("sum",pivot.getCTPivotTableDefinition().getDataFields().getDataFieldArray(0).getSubtotal().toString());assertTrue(pivot.getPivotCacheDefinition().getCTPivotCacheDefinition().getRefreshOnLoad());
            }
            for(String name:List.of("Channel history","Tool history"))assertEquals(1,book.getSheet(name).getDrawingPatriarch().getCharts().size());
            assertNull(book.getSheet("Bob summary").getDrawingPatriarch());
            assertEquals(166.5,book.getSheet("Bob summary").getRow(1).getCell(4).getNumericCellValue());assertEquals(1,book.getSheet("Eligible data").getTables().size());
        }
    }
}
