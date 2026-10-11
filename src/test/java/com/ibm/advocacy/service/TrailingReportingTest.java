package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;
import static org.junit.jupiter.api.Assertions.*;

class TrailingReportingTest {
    Row growth(String views,String weight) {return new Row("one",Map.of(VIEWS,views,"Weight",weight,"Impact hours","999","Metric basis","Trailing delta",URL,"https://example.com/Article"));}
    Data data(Row... rows) {return Data.output(new Data(),List.of(rows));}
    @Test void impactUsesExactDeltaBeforeReportingCeilingAndPreservesSource() {
        Row original=growth("433.5","0.5");
        Data enriched=TrailingReporting.enrich(data(original),null);
        Row report=Calculations.reported(enriched,false).rows.getFirst();
        assertEquals("434",report.get(VIEWS));assertEquals("433.5",report.get("Exact trailing growth"));
        assertEquals("216.75",report.get("Impact hours"));assertEquals("999",report.get("Original Impact hours"));
        assertEquals("999",original.get("Impact hours"));assertEquals("433.5",original.get(VIEWS));
        assertEquals("216.75",TrailingReporting.normalize(enriched.rows.getFirst()).get("Impact hours"));
        assertEquals("999",TrailingReporting.normalize(enriched.rows.getFirst()).get("Original Impact hours"));
    }
    @Test void retainedViewsDoNotClaimGrowthOrReuseWorkbookImpact() {
        Row retained=new Row("keep",Map.of(VIEWS,"9533","Weight","0.8","Impact hours","7626.4","Metric basis","Workbook content input — not a trailing delta"));
        Row report=TrailingReporting.normalize(retained);
        assertEquals("9533",report.get(VIEWS));assertEquals("retained",report.get("Growth classification"));
        assertEquals("",report.get("Exact trailing growth"));assertEquals("",report.get("Impact hours"));assertEquals("7626.4",report.get("Original Impact hours"));
    }
    @Test void missingWeightsAndBaselinesStayUnresolvedWithoutStaleImpact() {
        for(String weight:List.of("","invalid","-1")) {
            Row result=TrailingReporting.normalize(growth("10",weight));
            assertEquals("growth",result.get("Growth classification"));assertEquals("",result.get("Impact hours"));assertTrue(result.get("Impact basis").contains("Weight"));
        }
        Row invalid=growth("bad","0.5"),blank=growth("","0.5");
        assertEquals("unresolved",TrailingReporting.normalize(invalid).get("Growth classification"));assertEquals("",TrailingReporting.normalize(blank).get("Impact hours"));
        Row negative=TrailingReporting.normalize(growth("-0.5","0.5"));
        assertEquals("-0.25",negative.get("Impact hours"));assertEquals("-0.5",negative.get("Exact trailing growth"));
        assertEquals("0",TrailingReporting.normalize(growth("5","0")).get("Impact hours"));
    }
    @Test void summarySeparatesCategoriesAndMeasurementTimesFromImport() {
        Run run=new Run();run.createdAt="2026-10-10T01:00:00Z";run.workbook.put("historySha256","frozen-sha");
        Row measured=growth("10.5","0.5"),missingWeight=growth("1.5",""),unresolved=growth("","0.5");
        missingWeight.id="two";missingWeight.cells.put(URL,"https://example.com/Other");
        Row retained=new Row("retained",Map.of(VIEWS,"9000","Metric basis","Supplied historical input — not a trailing delta"));
        measured.cells.put(NOTE,"VIEWS collected 2026-10-11T00:00:00Z; evidence from immutable snapshot");
        Map<String,Object> report=TrailingReporting.summary(Calculations.reported(TrailingReporting.enrich(data(measured,missingWeight,unresolved,retained),null),false),run);
        assertEquals(2,report.get("growthRows"));assertEquals(1,report.get("retainedRows"));assertEquals(1,report.get("unresolvedRows"));assertEquals(1,report.get("missingWeightRows"));
        assertEquals("12",report.get("exactGrowth"));assertEquals("5.25",report.get("impactHours"));assertEquals("2026-10-11T00:00:00Z",report.get("firstMeasuredAt"));
        assertEquals(run.createdAt,report.get("snapshotImportedAt"));assertEquals(run.workbook,report.get("baseline"));
        assertEquals("",TrailingReporting.enrich(data(retained),null).rows.getFirst().get("Measured at"));
    }
    @Test void timestampsComeFromArtifactEvidenceAndRetainedRowsNeverClaimCollection() {
        Row old=growth("1","0.5");old.cells.put(NOTE,"VIEWS collected 2026-09-30T00:00:00Z; old measurement");
        assertEquals("2026-09-30T00:00:00Z",TrailingReporting.enrich(data(old),null).rows.getFirst().get("Measured at"));
        old.cells.put("Measured at","2026-10-01T00:00:00Z");
        assertEquals("2026-10-01T00:00:00Z",TrailingReporting.enrich(data(old),null).rows.getFirst().get("Measured at"));
        old.cells.remove("Measured at");old.cells.put(NOTE,"timestamp unavailable");
        assertEquals("",TrailingReporting.enrich(data(old),null).rows.getFirst().get("Measured at"));
        old.cells.put("Metric basis","Retained input — not a trailing delta");old.cells.put("Measured at","2026-10-01T00:00:00Z");
        assertEquals("",TrailingReporting.enrich(data(old),null).rows.getFirst().get("Measured at"));
    }
    @Test void overlapRequiresMatchingAttributionEventAndActivityWithAliasSupport() {
        Row history=growth("4","0.5");history.cells.putAll(Map.of("Date","2026-09-10","Author","Daniel Oh","Tool","IBM Runtimes","Name of activity","Article","Event","Conference","Type of activity","Recorded video"));
        Row current=history.copy();current.cells.remove("Date");current.cells.put("Date of activity","2026-09-10");current.cells.remove("Author");current.cells.put("Author of activity"," Daniel  Oh ");current.cells.put(URL,"http://www.example.com/Article#section");
        assertEquals("Yes",TrailingReporting.enrich(data(history),data(current)).rows.getFirst().get("Also in current.csv"));
        for(String field:List.of("Tool","Event","Type of activity","Name of activity")) {
            Row other=current.copy();other.cells.put(field,"Other");assertEquals("No",TrailingReporting.enrich(data(history),data(other)).rows.getFirst().get("Also in current.csv"));
        }
        Row caseDifferent=current.copy();caseDifferent.cells.put(URL,"https://example.com/article");
        assertEquals("No",TrailingReporting.enrich(data(history),data(caseDifferent)).rows.getFirst().get("Also in current.csv"));
        assertEquals("Not available yet",TrailingReporting.enrich(data(history),null).rows.getFirst().get("Also in current.csv"));
        assertEquals(1,TrailingReporting.enrich(data(history),data(current)).rows.size());
    }
}
