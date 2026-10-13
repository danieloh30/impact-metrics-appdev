package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.util.*;
import java.math.BigDecimal;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyReportingTest {
    Run run(){Run r=new Run();r.status="COMPLETED";r.workbook.put("month","2026-09");return r;}
    Row row(String id,String date,String tool,String type,String views){return new Row(id,Map.of("Date",date,"Author","Daniel Oh","Tool",tool,"Type of activity",type,"Name of activity",id,URL,"https://example.com/"+id,VIEWS,views));}
    Data data(Row... rows){return Data.output(new Data(),List.of(rows));}
    MonthlyReporting.Context context(Data snapshot){return new MonthlyReporting.Context(Map.of("recorded video",new MonthlyReporting.Rule("Views","0.5","Yes"),"customer workshop",new MonthlyReporting.Rule("Attendance hours","10","No"),"blog post / slide shares / .io updates",new MonthlyReporting.Rule("Views","0.5","Yes")),Map.of("daniel oh","AMER"),snapshot,true);}
    Row snapshot(Row row,String total,String previous){Row s=row.copy();s.cells.put("Current total",total);s.cells.put("Previous total",previous);return s;}
    @Test void currentImpactUsesApprovedAgentCountForSelectedMonthRatherThanOlderSnapshot(){
        Row live=row("video","2026-09-10","IBM Bob","Recorded video","9999");
        var report=MonthlyReporting.build(run(),data(live),new Data(),new Data(),"combined",context(data(snapshot(live,"166.5","0"))));
        assertEquals("4999.5",report.impact());assertEquals("9999",report.bob().getFirst().metrics());
        Row actual=report.details().rows.getFirst();assertEquals("AMER",actual.get("Geography"));assertEquals("0.5",actual.get("Weight"));assertEquals("Yes",actual.get("Trailing 90-day"));assertEquals("9999",actual.get("Current total"));assertEquals("9999",Calculations.reported(report.details(),false).rows.getFirst().get(VIEWS));assertEquals("9999",live.get(VIEWS));
    }
    @Test void trailingUsesAllocatedAgentTotalAndItsOriginalPreviousBaselineOnce(){
        Row live=row("old","2026-07-10","IBM Bob","Recorded video","10000");live.cells.put("Previous total","200");live.cells.put("Current total","10200");live.cells.put("Measured at","2026-10-11T12:00:00Z");live.cells.put("Metric basis","Trailing delta");
        var report=MonthlyReporting.build(run(),new Data(),data(live),new Data(),"trailing",context(data(snapshot(live,"166.5","150"))));
        assertEquals("5000",report.impact());assertEquals("10000",report.details().rows.getFirst().get("Exact reporting metric"));assertEquals("200",report.details().rows.getFirst().get("Previous total"));assertEquals("2026-10-11T12:00:00Z",report.details().rows.getFirst().get("Measured at"));assertEquals("2026-09-30",report.cutoff());
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
        assertEquals(2,report.included());assertEquals(2,report.excluded());assertEquals("99",report.impact());assertEquals(2,report.tool().size());assertEquals("99",report.bob().getFirst().metrics());
        var standalone=MonthlyReporting.build(run(),new Data(),data(history),new Data(),"trailing",context(data(snapshot(first,"50","20"))));assertEquals("15",standalone.impact());
    }
    @Test void missingOrAmbiguousSnapshotsAndFollowupsStayExplicitlyExcluded(){
        Row row=row("video","2026-09-10","IBM Bob","Recorded video","");
        var missing=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",context(new Data()));assertEquals(0,missing.included());assertTrue(missing.details().rows.getFirst().get("Reporting reason").contains("Reporting total unavailable"));
        Row snap=snapshot(row,"50","0");var ambiguous=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",context(data(snap,snap.copy())));assertEquals(0,ambiguous.included());assertTrue(ambiguous.details().rows.getFirst().get("Reporting reason").contains("Ambiguous"));
        var follow=MonthlyReporting.build(run(),data(row),new Data(),data(row),"current",context(data(snap)));assertEquals(0,follow.included());assertEquals("Unresolved follow-up activity",follow.details().rows.getFirst().get("Reporting reason"));
    }
    @Test void onlyAnExplicitMatchingMonthEndOverrideReplacesMissingSnapshot(){
        Run run=run();Row row=row("video","2026-09-10","IBM Bob","Recorded video","");
        run.monthEndValues.put("current:video",new MonthEndValue("100","2026-10-01","later", "2026-10-11T12:00:00Z"));assertEquals(0,MonthlyReporting.build(run,data(row),new Data(),new Data(),"current",context(new Data())).included());
        run.monthEndValues.put("current:video",new MonthEndValue("100","2026-09-30","dated analytics", "2026-10-11T12:00:00Z"));var report=MonthlyReporting.build(run,data(row),new Data(),new Data(),"current",context(new Data()));assertEquals("50",report.impact());assertEquals("2026-10-11T12:00:00Z",report.details().rows.getFirst().get("Recorded at"));
    }
    @Test void priorSnapshotIsOnlyABaselineWhileApprovedAgentCountNeedsNoNewConfirmation(){
        Row row=row("video","2026-09-10","IBM Bob","Recorded video","9999");var original=context(data(snapshot(row,"100","0")));
        var stale=new MonthlyReporting.Context(original.rules(),original.geography(),original.snapshot(),false);
        assertEquals(1,MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",stale).included());
        row.cells.put(VIEWS,"5");
        var negative=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"current",context(data(snapshot(row,"5","10"))));assertEquals(0,negative.included());assertTrue(negative.details().rows.getFirst().get("Reporting reason").contains("Negative"));
    }
    @Test void processedResultsRemainExactAndIdempotentWithoutASelectedMonthSnapshot(){
        Row current=row("dzone/0","2026-09-10","IBM Runtimes","Recorded video","1850");
        current.cells.put(NOTE,"DZONE collected 2026-10-11T12:00:00Z — approximately 3.7K allocated across two tools");
        Row trailing=row("history/0","2026-07-10","IBM Bob","Recorded video","1650");
        trailing.cells.put("Metric basis","Trailing delta");trailing.cells.put("Current total","1850");trailing.cells.put("Previous total","200");trailing.cells.put("Measured at","2026-10-11T12:00:00Z");
        var base=context(new Data());var stale=new MonthlyReporting.Context(base.rules(),base.geography(),base.snapshot(),false);
        var first=MonthlyReporting.build(run(),data(current),data(trailing),new Data(),"combined",stale);
        assertEquals(2,first.included());assertEquals("1750",first.impact());
        for(Row result:first.details().rows){assertEquals("2026-09",result.get("Reporting month"));assertEquals("2026-09-30",result.get("As of"));assertEquals("2026-10-11T12:00:00Z",result.get("Measured at"));assertEquals("",result.get("Reporting action"));}
        var again=MonthlyReporting.build(run(),data(first.details().rows.get(0)),data(first.details().rows.get(1)),new Data(),"combined",stale);
        assertEquals(first.impact(),again.impact());assertEquals("1650",again.details().rows.get(1).get("Exact reporting metric"));
        assertEquals("1850",current.get(VIEWS));assertEquals("200",trailing.get("Previous total"));
    }
    @Test void confirmationOnlyResolvesMissingTotalsNotOtherExclusions(){
        Row missing=row("missing","2026-09-10","IBM Bob","Recorded video","");
        var absent=MonthlyReporting.build(run(),data(missing),new Data(),new Data(),"current",context(new Data()));
        assertEquals("Confirm total",absent.details().rows.getFirst().get("Reporting action"));
        Row bad=missing.copy();bad.cells.put("Metric basis","Trailing delta");bad.cells.put("Current total","100");bad.cells.put("Previous total","");
        var baseline=MonthlyReporting.build(run(),new Data(),data(bad),new Data(),"trailing",context(new Data()));
        assertTrue(baseline.details().rows.getFirst().get("Reporting reason").contains("Previous baseline"));assertEquals("",baseline.details().rows.getFirst().get("Reporting action"));
        Row retained=missing.copy();retained.cells.put("Metric basis","Supplied historical input — not a trailing delta");
        var kept=MonthlyReporting.build(run(),new Data(),data(retained),new Data(),"trailing",context(new Data()));assertEquals("",kept.details().rows.getFirst().get("Reporting action"));
    }
    @Test void combinedHistoryIsOmittedOnlyWhenTheCurrentContributionIsActuallyIncluded(){
        Row current=row("video","2026-09-10","IBM Bob","Recorded video","");Row historical=current.copy();historical.id="history";historical.cells.put("Metric basis","Trailing delta");historical.cells.put("Current total","100");historical.cells.put("Previous total","60");historical.cells.put(VIEWS,"40");
        var report=MonthlyReporting.build(run(),data(current),data(historical),data(current),"combined",context(new Data()));assertEquals(1,report.included());assertEquals("20",report.impact());assertEquals("Included",report.details().rows.getLast().get("Reporting handling"));assertEquals("20",report.details().rows.getLast().get("Counted impact hours"));
        current.cells.put(VIEWS,"100");report=MonthlyReporting.build(run(),data(current),data(historical),new Data(),"combined",context(new Data()));assertEquals("50",report.impact());assertEquals("Already counted",report.details().rows.getLast().get("Reporting handling"));assertEquals("0",report.details().rows.getLast().get("Counted impact hours"));assertTrue(report.details().rows.getLast().get("Reporting guidance").contains("No action required"));assertFalse(report.warnings().stream().anyMatch(warning->warning.contains("need attention")));
    }
    @Test void sourceContextAndSnapshotFallbackAreAvailableWhenNoAgentTotalExists(){
        Run r=run();r.workbook.put("filename","AvocadoBot.xlsx");r.workbook.put("sourceUrl","https://ibm.sharepoint.com/current.xlsx");r.workbook.put("currentSheet","Current month inputs");
        Row input=row("Current month inputs:12/0","2026-09-10","IBM Bob","Recorded video","");
        var report=MonthlyReporting.build(r,data(input),new Data(),new Data(),"current",context(data(snapshot(input,"166.5","0"))));
        Row result=report.details().rows.getFirst();assertEquals("83.25",report.impact());assertEquals("AvocadoBot.xlsx",result.get("Source workbook"));assertEquals("Current month inputs",result.get("Source sheet"));assertEquals("Current month inputs:12",result.get("Source row"));assertEquals("https://ibm.sharepoint.com/current.xlsx",result.get("Source workbook URL"));assertEquals("https://example.com/Current month inputs:12/0",result.get(URL));
        assertEquals("167",Calculations.reported(report.details(),false).rows.getFirst().get(VIEWS));
    }
    @Test void aFutureReportingMonthCannotProduceTotals(){
        Run run=run();run.workbook.put("month",java.time.YearMonth.now().plusMonths(1).toString());
        Row workshop=row("workshop","2026-09-01","IBM Bob","Customer workshop","25");
        var report=MonthlyReporting.build(run,data(workshop),new Data(),new Data(),"current",context(new Data()));
        assertEquals(0,report.included());assertTrue(report.details().rows.getFirst().get("Reporting reason").contains("has not started"));
    }
    @Test void interimOctoberReportIncludesCollectedCurrentAndTrailingAndExcludesFutureDelivery(){
        Run run=run();run.workbook.put("month","2026-10");run.workbook.put("asOf","2026-10-13");
        Row current=row("oct-video","2026-10-02","IBM Runtimes","Recorded video","500");
        Row future=row("future-video","2026-10-20","IBM Runtimes","Recorded video","100");
        Row history=row("old-video","2026-08-01","IBM Bob","Recorded video","40");history.cells.put("Metric basis","Trailing delta");history.cells.put("Current total","140");history.cells.put("Previous total","100");
        var report=MonthlyReporting.build(run,data(current,future),data(history),new Data(),"combined",context(new Data()));
        assertEquals("270",report.impact());assertEquals(2,report.included());assertTrue(report.provisional());assertEquals("2026-10-13",report.cutoff());
        assertTrue(report.details().rows.get(1).get("Reporting reason").contains("after this run's reporting date"));assertEquals("",report.details().rows.get(1).get("Reporting action"));
        assertEquals("500",report.details().rows.getFirst().get("Current total"));assertEquals("40",report.details().rows.getLast().get("Exact reporting metric"));
        assertTrue(report.warnings().stream().anyMatch(w->w.contains("Provisional snapshot as of 2026-10-13")));
    }
    @Test void laterOctoberRunIncludesNewActivitiesWhileLastDayAndNovemberRunsAreFinal(){
        Row current=row("oct-video","2026-10-20","IBM Runtimes","Recorded video","500");
        for(String date:List.of("2026-10-20","2026-10-31","2026-11-01")){
            Run run=run();run.workbook.put("month","2026-10");run.workbook.put("asOf",date);
            var report=MonthlyReporting.build(run,data(current),new Data(),new Data(),"current",context(new Data()));
            assertEquals("250",report.impact());assertEquals(date.equals("2026-10-20")?"2026-10-20":"2026-10-31",report.cutoff());assertEquals(date.equals("2026-10-20"),report.provisional());
        }
    }
    @Test void nativeExcelPivotsUseSumAndChartsReconcileWithTypedExactDetail() throws Exception {
        Row row=row("video","2026-09-10","IBM Bob","Recorded video","166.5");var report=MonthlyReporting.build(run(),data(row),new Data(),new Data(),"combined",context(data(snapshot(row,"166.5","0"))));
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
    @Test void toolTotalsConserveAttributedImpactAndUseSourceToolsAcrossCurrentAndGrowth(){
        Row shared=row("shared","2026-09-10","IBM Bob;Independent open source projects","Customer workshop","25");shared.cells.put("Author","Daniel Oh;Kevin Dubois");var shares=Calculations.split(shared,VIEWS);List<Row> current=new ArrayList<>(shares);current.add(shares.getFirst().copy());
        Row old=row("old-video","2026-07-01","IBM Bob","Recorded video","20");old.cells.put("Metric basis","Trailing delta");old.cells.put("Current total","80");old.cells.put("Previous total","60");
        var report=MonthlyReporting.build(run(),data(current.toArray(Row[]::new)),data(old),new Data(),"combined",context(new Data()));assertEquals("260",report.impact());assertEquals(5,report.included());assertEquals(1,report.excluded());
        assertEquals("135",report.tool().stream().filter(tool->tool.name().equals("IBM Bob")).findFirst().orElseThrow().impact());assertEquals("125",report.tool().stream().filter(tool->tool.name().equals("Independent open source projects")).findFirst().orElseThrow().impact());
        assertEquals(0,Calculations.number(report.impact()).compareTo(report.tool().stream().map(tool->Calculations.number(tool.impact())).reduce(BigDecimal.ZERO,BigDecimal::add)));assertEquals("25",shared.get(VIEWS));assertEquals("IBM Bob;Independent open source projects",shared.get("Tool"));
    }

}
