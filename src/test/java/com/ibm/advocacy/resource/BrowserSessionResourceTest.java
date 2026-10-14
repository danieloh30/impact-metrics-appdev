package com.ibm.advocacy.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

@QuarkusTest
class BrowserSessionResourceTest {
    @Test void importsAreUnavailableInLocalBrowserModeAndNeverReturnSessionSecrets() {
        for(String provider:new String[]{"sharepoint","linkedin"})given().contentType("application/json").body("{\"url\":\"https://ibm.sharepoint.com/sites/Test/Book.xlsx\",\"state\":{\"cookies\":[],\"origins\":[]}}")
                .post("/api/browser-sessions/"+provider).then().statusCode(400);
    }
    @Test void rejectsUnknownProvidersAndMissingBodies() {
        given().contentType("application/json").body("{}").post("/api/browser-sessions/unknown").then().statusCode(404);
        given().contentType("application/json").body("null").post("/api/browser-sessions/linkedin").then().statusCode(400);
    }
    @Test void rejectsFormAndCrossSiteSimpleRequestPayloads() {
        given().contentType("application/x-www-form-urlencoded").body("state=synthetic").post("/api/browser-sessions/linkedin").then().statusCode(415);
    }
}
