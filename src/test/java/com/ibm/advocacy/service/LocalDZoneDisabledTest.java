package com.ibm.advocacy.service;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
@QuarkusTest class LocalDZoneDisabledTest {
 @Test void localDevDefaultsDoNotExposeCloudCollection(){
  given().get("/api/config").then().statusCode(200).body("dzoneLocalCollection",is(false));
  given().contentType("application/json").body(Map.of("version",1,"confirmed",true,"values",List.of(Map.of("rowId","c1","value","100","evidence","Synthetic")))).post("/api/runs/00000000-0000-0000-0000-000000000000/dzone/measurements").then().statusCode(404);
 }
}
