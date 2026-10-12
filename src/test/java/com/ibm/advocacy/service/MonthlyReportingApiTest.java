package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class MonthlyReportingApiTest {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject MonthlyReporting reporting;
    void row(Sheet sheet,int n,String... values){var row=sheet.createRow(n);for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}
    byte[] history(boolean duplicate) throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet model=book.createSheet("DA metric model 2026");row(model,0,"Outbound/Inbound","Trailing 90 days?","Activities","Media type","Unit Measure","Weight");row(model,1,"Outbound","No","Customer workshop","workshop","Attendance hours","10");if(duplicate)row(model,2,"Outbound","No","Customer workshop","workshop","Attendance hours","2");
            row(model,3,"Outbound","Yes","Recorded video","video","Views","0.5");
            Sheet old=book.createSheet("DA metric model");row(old,0,"Activities","Weight");row(old,1,"Customer workshop","2");
            Sheet snapshot=book.createSheet("September inputs");row(snapshot,0,"Date","Author","Tool","Name of activity","Type of activity","Link / URL to resource","Current total","Previous total","Trailing 90-day","Views / attendance");row(snapshot,1,"2026-09-10","Daniel Oh","IBM Bob","Workshop","Customer workshop","","10","0","No","10");
            Sheet ranges=book.createSheet("Ranges");row(ranges,0,"","","","","","","","Author","Geography");row(ranges,1,"","","","","","","","Daniel Oh","AMER");
            book.write(out);return out.toByteArray();
        }
    }
    Run fixture() throws Exception {
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
        Run run=runs.create("Date,Author,Tool,Name of activity,Type of activity,Views / attendance,Link / URL to resource\n2026-09-10,Daniel Oh,IBM Bob,Workshop,Customer workshop,10,\n".getBytes(),null);
        run.status="COMPLETED";run.workbook.put("month","2026-09");run.workbook.put("historySheets",List.of("September inputs"));run.workbook.put("reportingPolicy","MONTH_END");
        workspace.bytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"),history(false));
        Data data=workspace.data(run.id,"source");List<Row> rows=new ArrayList<>();for(Row source:data.rows)rows.add(Calculations.finalRow(source));
        run.version=1;run.phase=7;run.artifacts.put("current.csv",new Artifact("current.csv",1,7,1,"fixture"));workspace.bytes(workspace.runPath(run.id,"output/1/current.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(new Data(com.ibm.advocacy.model.MetricsSchema.FINAL_HEADERS,rows)));workspace.save(run);return run;
    }
    @Test void modelLookupUsesOnly2026SheetAndRejectsDuplicateRules() throws Exception {
        Run run=fixture();var model=reporting.context(run);assertEquals("10",model.rules().get("customer workshop").weight());assertEquals("AMER",model.geography().get("daniel oh"));assertTrue(model.sameMonth());
        workspace.bytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"),history(true));assertThrows(jakarta.ws.rs.BadRequestException.class,()->reporting.context(run));
    }
    @Test void apiPivotsAndBothDownloadsShareTheSameExactTotals() throws Exception {
        Run run=fixture();given().get("/api/runs/"+run.id+"/reporting").then().statusCode(200).body("impact",equalTo("100")).body("included",equalTo(1)).body("bob[0].metrics",equalTo("10"));
        given().get("/api/runs/"+run.id+"/reporting?scope=bad").then().statusCode(400);
        String csv=given().get("/api/runs/"+run.id+"/reporting/download?format=csv").then().statusCode(200).contentType("text/csv").extract().asString();assertTrue(csv.contains("Impact hours"));assertTrue(csv.contains("100"));
        byte[] excel=given().get("/api/runs/"+run.id+"/reporting/download?format=xlsx").then().statusCode(200).contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").extract().asByteArray();try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(excel))){assertEquals(100,book.getSheet("Report").getRow(5).getCell(1).getNumericCellValue());}
        Row row=runs.artifactData(run.id,"current.csv",null).rows.getFirst();assertEquals("10",row.get("Weight"));assertEquals("100",row.get("Impact hours"));assertEquals("AMER",row.get("Geography"));
    }
    @Test void monthEndConfirmationRejectsWrongCutoffUnknownRowsAndStaleRevision() throws Exception {
        Run run=fixture();String rowId=workspace.data(run.id,"source").rows.getFirst().id;
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->runs.confirmMonthEnd(run.id,0,"current",rowId,"100","2026-10-01",""));
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->runs.confirmMonthEnd(run.id,0,"current","missing","100","2026-09-30",""));
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->runs.confirmMonthEnd(run.id,0,"current",rowId,"-1","2026-09-30",""));
        runs.confirmMonthEnd(run.id,0,"current",rowId,"100","2026-09-30",null);assertEquals(1,runs.get(run.id).reportingRevision);
        assertEquals("1000",runs.report(run.id,"current").impact());
        assertFalse(runs.report(run.id,"current").details().rows.getFirst().get("Recorded at").isBlank());
        assertThrows(jakarta.ws.rs.ClientErrorException.class,()->runs.confirmMonthEnd(run.id,0,"current",rowId,"100","2026-09-30",""));
        Run saved=runs.get(run.id);saved.status="READY";workspace.save(saved);assertThrows(jakarta.ws.rs.ClientErrorException.class,()->runs.confirmMonthEnd(run.id,1,"current",rowId,"100","2026-09-30",""));
    }
    @Test void existingApprovedAgentCountIsAssignedToSeptemberInApiAndDownloadsWithoutReconfirmation() throws Exception {
        Run run=fixture();run.workbook.put("filename","AvocadoBot.xlsx");run.workbook.put("currentSheet","Current month inputs");run.workbook.put("sourceUrl","https://ibm.sharepoint.com/current.xlsx");
        Row row=new Row("Current month inputs:23/0",Map.of("Date","2026-09-10","Author","Daniel Oh","Tool","IBM Runtimes","Type of activity","Recorded video","Name of activity","DZone article","Link / URL to resource","https://dzone.com/articles/goose-agentgateway-security","Views / attendance","","Pre-report Views / attendance","1850","Pre-report Current total","1850","Scrape notes","Month-end reporting: excluded"));
        row.cells.put("Live collection note","DZONE collected 2026-10-11T12:00:00Z — approximately 3.7K views, split across two tools");
        byte[] original=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Data.output(new Data(),List.of(row)));
        workspace.bytes(workspace.runPath(run.id,"output/1/current.json"),original);workspace.save(run);
        given().get("/api/runs/"+run.id+"/reporting").then().statusCode(200).body("included",equalTo(1)).body("excluded",equalTo(0)).body("month",equalTo("2026-09")).body("impact",equalTo("925")).body("details.rows[0].cells.'Current total'",equalTo("1850")).body("details.rows[0].cells.'Measured at'",equalTo("2026-10-11T12:00:00Z")).body("details.rows[0].cells.'As of'",equalTo("2026-09-30")).body("details.rows[0].cells.'Reporting action'",equalTo(""));
        Row exported=runs.artifactData(run.id,"current.csv",null).rows.getFirst();assertEquals("1850",exported.get("Views / attendance"));assertEquals("925",exported.get("Impact hours"));assertEquals("https://ibm.sharepoint.com/current.xlsx",exported.get("Source workbook URL"));assertEquals("Current month inputs",exported.get("Source sheet"));
        byte[] excel=runs.reportingDownload(run.id,"combined",true);try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(excel))){assertEquals(925,book.getSheet("Report").getRow(5).getCell(1).getNumericCellValue());}
        assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"output/1/current.json")));assertTrue(runs.get(run.id).monthEndValues.isEmpty());
    }
@Test void negativeConfirmationIsRejectedWithoutSavingAndCorrectedTotalResolves() throws Exception {
    Run run=fixture();Row row=new Row("September inputs:153/0",Map.of("Date","2026-09-10","Author","Markus Eisele","Tool","IBM Bob","Type of activity","Recorded video","Name of activity","Storage article","Current total","1150","Previous total","2100","Metric basis","Trailing delta","Views / attendance","-950"));
    byte[] original=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Data.output(new Data(),List.of(row)));
    workspace.bytes(workspace.runPath(run.id,"output/1/updated_90-day.json"),original);run.artifacts.put("updated_90-day.csv",new Artifact("updated_90-day.csv",1,4,1,"fixture"));
    run.monthEndValues.put("trailing:"+row.id,new MonthEndValue("1150","2026-09-30","Old invalid confirmation","2026-10-12T12:00:00Z"));workspace.save(run);
    var invalid=assertThrows(jakarta.ws.rs.BadRequestException.class,()->runs.confirmMonthEnd(run.id,0,"trailing",row.id,"1150","2026-09-30",""));assertTrue(invalid.getMessage().contains("below previous baseline 2100"));assertEquals(0,runs.get(run.id).reportingRevision);assertEquals("1150",runs.get(run.id).monthEndValues.get("trailing:"+row.id).value());assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"output/1/updated_90-day.json")));
    runs.confirmMonthEnd(run.id,0,"trailing",row.id,"2300","2026-09-30","Verified attributed share in fixture");assertEquals("100",runs.report(run.id,"trailing").impact());assertEquals(1,runs.report(run.id,"trailing").included());
    runs.confirmMonthEnd(run.id,1,"trailing",row.id,"2100","2026-09-30","Verified unchanged share in fixture");assertEquals("0",runs.report(run.id,"trailing").impact());assertEquals(1,runs.report(run.id,"trailing").included());
}
    @Test void chartHistorySnapshotImportsSeparatelyAndDoesNotReplaceAgentSources() throws Exception {
        Run run=fixture();byte[] original=Files.readAllBytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"));
        String url="https://ibm.sharepoint.com/sites/DEPT-DeveloperRelations/Shared%20Documents/Advocacy%20RUN%20metrics%202026.xlsx";
        given().contentType("application/json").body(Map.of("revision",0,"url",url)).post("/api/runs/"+run.id+"/reporting/history").then().statusCode(200).body("reportingRevision",equalTo(1));
        assertArrayEquals(original,Files.readAllBytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx")));assertTrue(Files.isRegularFile(workspace.runPath(run.id,"rawdata/chart-history-1.xlsx")));
        given().get("/api/runs/"+run.id+"/reporting").then().statusCode(200).body("trends.channel.months[0]",equalTo("2025-09")).body("trends.channel.months[12]",equalTo("2026-09")).body("trends.tool.series[0].values[12]",equalTo("100")).body("trends.source.endMonth",equalTo("2026-09"));
        given().contentType("application/json").body(Map.of("revision",0,"url",url)).post("/api/runs/"+run.id+"/reporting/history").then().statusCode(409);
        given().contentType("application/json").body(Map.of("revision",1,"url",url,"endMonth","2026-08")).post("/api/runs/"+run.id+"/reporting/history").then().statusCode(400);assertEquals(1,runs.get(run.id).reportingRevision);
        byte[] firstSnapshot=Files.readAllBytes(workspace.runPath(run.id,"rawdata/chart-history-1.xlsx"));
        runs.importChartHistory(run.id,1,url,null);assertArrayEquals(firstSnapshot,Files.readAllBytes(workspace.runPath(run.id,"rawdata/chart-history-1.xlsx")));assertTrue(Files.isRegularFile(workspace.runPath(run.id,"rawdata/chart-history-2.xlsx")));
    }
}
