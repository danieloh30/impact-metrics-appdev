package com.ibm.advocacy.service;

import com.ibm.advocacy.collector.MetricCollectors;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Measurement;
import com.ibm.advocacy.model.Row;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.nio.charset.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

class CalculationsTest {
    Row row(Map<String,String> fields) { return new Row("c1",fields); }
    @Test void cartesianSplitConservesDigitalMetric() {
        List<Row> rows=Calculations.split(row(Map.of("Author of activity","A; B","Tool","X; Y",VIEWS,"100")),VIEWS);
        assertEquals(4,rows.size());assertTrue(rows.stream().allMatch(r->r.get(VIEWS).equals("25")));
        assertEquals(Set.of("A/X","A/Y","B/X","B/Y"),new HashSet<>(rows.stream().map(r->r.get("Author of activity")+"/"+r.get("Tool")).toList()));
    }
    @Test void attendanceSplitWritesAllocatedHoursIntoViews() {
        Row source=row(Map.of("Author of activity","A; B","Tool","X; Y",VIEWS,"25","Duration (in hours)","2"));
        source.cells.put("Attendance hours",Calculations.format(Calculations.attendance(source)));
        List<Row> rows=Calculations.split(source,"Attendance hours");assertEquals(4,rows.size());
        assertTrue(rows.stream().allMatch(r->r.get(VIEWS).equals("12.5")&&r.get("Attendance hours").equals("12.5")));
    }
    @Test void durationMinimumAndInvalidAttendanceAreDistinct() {
        assertEquals(new BigDecimal("10"),Calculations.attendance(row(Map.of(VIEWS,"10","Duration (in hours)",".25"))));
        assertEquals(new BigDecimal("10"),Calculations.attendance(row(Map.of(VIEWS,"10"))));
        assertEquals(BigDecimal.ZERO,Calculations.attendance(row(Map.of(VIEWS,"0"))));
        assertThrows(IllegalArgumentException.class,()->Calculations.attendance(row(Map.of(VIEWS,""))));
        assertThrows(IllegalArgumentException.class,()->Calculations.attendance(row(Map.of(VIEWS,"bad"))));
    }
    @Test void roundingPreservesTotalsIncludingNegativeValues() {
        for(String value:List.of("1","-1","123.456789","0"))for(int count:List.of(3,7,11)) {
            BigDecimal original=new BigDecimal(value);BigDecimal sum=Calculations.allocate(original,count).stream().reduce(BigDecimal.ZERO,BigDecimal::add);assertEquals(0,sum.compareTo(original));
        }
    }
    @Test void followUpNamesSplitWithoutInventingMetric() {
        List<Row> rows=Calculations.split(row(Map.of("Author","A; B","Tool","X; Y",VIEWS,"")),null);
        assertEquals(4,rows.size());assertTrue(rows.stream().allMatch(r->r.get(VIEWS).isEmpty()));
    }
    @Test void trailingAllocatesSharedTotalAndPreservesBaselines() {
        String url="https://www.youtube.com/watch?v=one";
        Row first=new Row("t1",Map.of(URL,url,"Current total","40")),second=new Row("t2",Map.of(URL,url+"#fragment","Current total","40"));
        var measured=Map.of(url,new Measurement("120","VIEWS",url,"now","test",true));
        List<Row> rows=Calculations.trailing(List.of(first,second),measured);
        assertTrue(rows.stream().allMatch(r->r.get("Current total").equals("60")&&r.get("Previous total").equals("40")&&r.get(VIEWS).equals("20")));
        assertEquals("40",first.get("Current total"));
    }
    @Test void missingTrailingMetricsDoNotPretendZeroDelta() {
        Row source=row(Map.of(URL,"","Current total","40",VIEWS,"12"));Row result=Calculations.trailing(List.of(source),Map.of()).getFirst();
        assertEquals("40",result.get("Current total"));assertEquals("40",result.get("Previous total"));assertEquals("",result.get(VIEWS));
    }
    @Test void negativeTrailingDeltasRemainVisible() {
        String url="https://www.youtube.com/watch?v=one";Row source=row(Map.of(URL,url,"Current total","50"));
        Row result=Calculations.trailing(List.of(source),Map.of(url,new Measurement("40","VIEWS",url,"now","test",true))).getFirst();
        assertEquals("-10",result.get(VIEWS));assertTrue(result.get(NOTE).startsWith("Negative delta"));
    }
    @Test void csvHandlesEncodingQuotedContentAndHeaderNormalization() throws Exception {
        CsvCodec codec=new CsvCodec();
        String input="\ufeffAuthor of activity,Tool,Type of activity,Link / URL\u00a0to resource,Notes / description\r\nA,X,Recorded video,\"\",\"comma, and\nnewline\"\r\n";
        Data data=codec.read(input.getBytes(StandardCharsets.UTF_8),false);assertEquals("comma, and\nnewline",data.rows.getFirst().get("Notes / description"));assertTrue(data.headers.contains(URL));
        Data roundtrip=codec.read(codec.write(data),false);assertEquals(data.rows.getFirst().cells,roundtrip.rows.getFirst().cells);
        String past="Author,Type of activity,Link / URL to resource,Current total,Notes / description,,\r\nA,Recorded video,,10,café,,\r\n";
        Data trailing=codec.read(past.getBytes(Charset.forName("windows-1252")),true);assertEquals("café",trailing.rows.getFirst().get("Notes / description"));assertEquals(5,trailing.headers.size());
    }
    @Test void csvRejectsNormalizedHeaderCollisionAndMalformedQuotes() {
        CsvCodec codec=new CsvCodec();assertThrows(IllegalArgumentException.class,()->codec.read("Author,Author ,Type of activity,Link / URL to resource\nA,A,Recorded video,\n".getBytes(),false));
        assertThrows(Exception.class,()->codec.read("Author,Type of activity,Link / URL to resource\n\"unclosed".getBytes(),false));
    }
    @Test void finalSchemaMapsAliasesWithoutDroppingNamedFields() {
        Row output=Calculations.finalRow(row(Map.of("Date of activity","2026-07-01","Author of activity","A","Name of event","Event","Strategic initiative","One",VIEWS,"5")));
        assertEquals(FINAL_HEADERS,new ArrayList<>(output.cells.keySet()));assertEquals("A",output.get("Author"));assertEquals("Event",output.get("Event"));assertEquals("One",output.get("Strategic initiative"));
    }
    @Test void metricsDoNotConfuseLinkedInVideoViewsAndReactions() {
        assertEquals("VIDEO_VIEWS",MetricCollectors.type("https://www.linkedin.com/events/123/"));assertEquals("REACTIONS",MetricCollectors.type("https://www.linkedin.com/posts/a"));
        assertEquals("STARS",MetricCollectors.type("https://github.com/a/b"));assertEquals("VIEWS",MetricCollectors.type("https://github.com.evil.test/a/b"));
    }
}
