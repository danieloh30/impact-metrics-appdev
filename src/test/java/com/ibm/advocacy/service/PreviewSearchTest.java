package com.ibm.advocacy.service;
import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest
class PreviewSearchTest {
 @Inject RuntimeWorkspace workspace;
 @Inject ResultStore results;
 @Inject RunService runs;
 Run fixture() throws Exception {
  Run run=new Run();run.id=UUID.randomUUID().toString();run.createdAt=Instant.now().toString();run.phase=7;run.status="COMPLETED";run.version=2;
  List<Row> rows=new ArrayList<>();
  for(int i=0;i<60;i++)rows.add(new Row("row-"+i,Map.of("Author",i<30?"Daniel Oh":"Alex Soto","Tool","IBM Runtimes","Name of activity",i==59?"Élder & Quarkus [Guide]":"Activity "+i,"Views / attendance","1850","Link / URL to resource","https://example.org/"+i,"hidden","secret")));
  Data data=new Data(List.of("Author","Tool","Name of activity","Views / attendance","Link / URL to resource"),rows);
  run.artifacts.put("current.csv",results.save(run.id,"current.csv",1,7,data));
  results.save(run.id,"current.csv",2,7,new Data(data.headers,List.of(rows.getFirst())));
  workspace.save(run);return run;
 }
 String url(Run r){return "/api/runs/"+r.id+"/artifacts/current.csv/preview";}
 @Test void searchesAllDisplayedColumnsBeforePaginationWithoutChangingVersionsOrDownloads() throws Exception {
  Run r=fixture();byte[] before=Files.readAllBytes(workspace.runPath(r.id,"output/1/current.json"));byte[] export=runs.artifact(r.id,"current.csv",1);
  given().queryParam("q","  aLeX sOtO  ").queryParam("limit",25).get(url(r)).then().statusCode(200).body("total",is(30)).body("unfilteredTotal",is(60)).body("rows.size()",is(25)).body("rows[0].id",is("row-30"));
  given().queryParam("q","Alex Soto").queryParam("offset",25).queryParam("limit",25).get(url(r)).then().statusCode(200).body("rows.size()",is(5)).body("rows[0].id",is("row-55"));
  for(String term:List.of("ÉLDER","[Guide]","example.org/59"))given().queryParam("q",term).get(url(r)).then().statusCode(200).body("total",is(1)).body("rows[0].id",is("row-59"));
  for(String term:List.of("RUNTIMES","1850"))given().queryParam("q",term).get(url(r)).then().statusCode(200).body("total",is(60));
  given().queryParam("version",2).queryParam("q","Alex").get(url(r)).then().statusCode(200).body("total",is(0)).body("unfilteredTotal",is(1));
  assertArrayEquals(before,Files.readAllBytes(workspace.runPath(r.id,"output/1/current.json")));assertArrayEquals(export,runs.artifact(r.id,"current.csv",1));
 }
 @Test void emptySearchNoMatchesAndInvalidQueryAreHandled() throws Exception {
  Run r=fixture();
  for(String term:List.of("","   "))given().queryParam("q",term).get(url(r)).then().statusCode(200).body("total",is(60));
  given().get(url(r)).then().statusCode(200).body("total",is(60));
  for(String term:List.of("secret","absent",".*"))given().queryParam("q",term).get(url(r)).then().statusCode(200).body("total",is(0)).body("rows.size()",is(0));
  given().queryParam("q","Alex").queryParam("offset",99).get(url(r)).then().statusCode(200).body("total",is(30)).body("rows.size()",is(0));
  given().queryParam("q","x".repeat(201)).get(url(r)).then().statusCode(400);
  given().queryParam("q","Alex").queryParam("offset",-1).get(url(r)).then().statusCode(400);
 }
}
