package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Run;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportingPeriodTest {
    Run run(String date){Run r=new Run();r.workbook.put("month","2026-10");r.workbook.put("asOf",date);return r;}
    @Test void dailyReportingDatesStayPinnedAndFinalCutoffNeverMovesPastMonthEnd(){
        for(String date:new String[]{"2026-10-13","2026-10-20","2026-10-31","2026-11-01"}){
            Run r=run(date);boolean interim=date.compareTo("2026-10-31")<0;
            assertEquals(interim?date:"2026-10-31",ReportingPeriod.cutoff(r));assertEquals(interim,ReportingPeriod.provisional(r));assertEquals(interim?"PROVISIONAL":"COMPLETED",ReportingPeriod.publicationStage(r));
            r.createdAt="2026-12-01T12:00:00Z";assertEquals(interim,ReportingPeriod.provisional(r));assertEquals(interim?date:"2026-10-31",ReportingPeriod.cutoff(r));
        }
    }
    @Test void legacyRunFallsBackToItsCreationDateRatherThanTodaysDate(){
        Run r=run("2026-10-13");r.workbook.remove("asOf");r.createdAt="2026-10-13T12:00:00Z";assertTrue(ReportingPeriod.provisional(r));assertEquals("2026-10-13",ReportingPeriod.cutoff(r));
    }
}
