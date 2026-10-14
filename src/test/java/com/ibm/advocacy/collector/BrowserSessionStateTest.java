package com.ibm.advocacy.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionStateTest {
    final ObjectMapper mapper=new ObjectMapper();
    String cookie(String domain,String name,String expires){return "{\"name\":\""+name+"\",\"value\":\"SYNTHETIC-ONLY\",\"domain\":\""+domain+"\",\"path\":\"/\",\"expires\":"+expires+"}";}
    String filter(String cookies,String origins) throws Exception {return BrowserSessionState.filter(mapper,mapper.readTree("{\"cookies\":["+cookies+"],\"origins\":["+origins+"]}"),Set.of("ibm.sharepoint.com"),"FedAuth");}
    @Test void keepsProviderStateAndIndexedDbButDropsAppLoginAndUnrelatedCookies() throws Exception {
        var result=mapper.readTree(filter(cookie(".ibm.sharepoint.com","FedAuth","-1")+","+cookie("app.example.com","oauth","-1"),"{\"origin\":\"https://ibm.sharepoint.com\",\"localStorage\":[],\"indexedDB\":[{\"name\":\"auth\"}]},{\"origin\":\"https://app.example.com\",\"localStorage\":[]}"));
        assertEquals(1,result.path("cookies").size());assertEquals(1,result.path("origins").size());assertEquals("auth",result.path("origins").get(0).path("indexedDB").get(0).path("name").asText());
    }
    @Test void rejectsMissingProviderAuthentication() {assertThrows(BadRequestException.class,()->filter(cookie("app.example.com","FedAuth","-1"),""));}
    @Test void rejectsExpiredAuthentication() {assertThrows(BadRequestException.class,()->filter(cookie("ibm.sharepoint.com","FedAuth","1"),""));}
    @Test void rejectsSuperdomainsAndSuffixImpersonation() {for(String domain:Set.of("com","sharepoint.com","evil-ibm.sharepoint.com","ibm.sharepoint.com.evil.com"))assertThrows(BadRequestException.class,()->filter(cookie(domain,"FedAuth","-1"),""));}
    @Test void keepsOnlyNamedSharePointRootRefreshCookieAlongsideTenantAuthentication() throws Exception {
        var result=mapper.readTree(filter(cookie("ibm.sharepoint.com","FedAuth","-1")+","+cookie(".sharepoint.com","rtFa","-1")+","+cookie(".sharepoint.com","unrelated","-1")+","+cookie(".sharepoint.com","FedAuth","-1")+","+cookie("sharepoint.com.evil.com","rtFa","-1"),"{\"origin\":\"https://sharepoint.com\"}"));
        assertEquals(2,result.path("cookies").size());assertEquals("rtFa",result.path("cookies").get(1).path("name").asText());assertEquals(0,result.path("origins").size());
        assertThrows(BadRequestException.class,()->filter(cookie(".sharepoint.com","rtFa","-1"),""),"Root refresh cookie alone is not IBM-host authentication");
    }
    @Test void expiredRootRefreshCookieIsNotTransferred() throws Exception {
        assertEquals(1,mapper.readTree(filter(cookie("ibm.sharepoint.com","FedAuth","-1")+","+cookie(".sharepoint.com","rtFa","1"),"")).path("cookies").size());
    }
    @Test void rejectsInvalidShapesAndOversizedState() throws Exception {
        for(String json:Set.of("{}","null","{\"cookies\":{},\"origins\":[]}"))assertThrows(BadRequestException.class,()->BrowserSessionState.filter(mapper,mapper.readTree(json),Set.of("linkedin.com"),"li_at"));
        var huge=mapper.createObjectNode();huge.putArray("cookies");huge.putArray("origins");huge.put("extra","x".repeat(2_000_001));assertThrows(BadRequestException.class,()->BrowserSessionState.filter(mapper,huge,Set.of("linkedin.com"),"li_at"));
    }
    @Test void excludesUntrustedOriginSchemesPortsAndPaths() throws Exception {
        String origins="{\"origin\":\"http://ibm.sharepoint.com\"},{\"origin\":\"https://ibm.sharepoint.com:444\"},{\"origin\":\"https://ibm.sharepoint.com/path\"},{\"origin\":\"https://ibm.sharepoint.com@evil.com\"}";
        assertEquals(0,mapper.readTree(filter(cookie("ibm.sharepoint.com","FedAuth","-1"),origins)).path("origins").size());
    }
    @Test void linkedinRequiresItsOwnAuthenticationCookie() throws Exception {
        var state=mapper.readTree("{\"cookies\":["+cookie(".linkedin.com","li_at","-1")+","+cookie("ibm.sharepoint.com","FedAuth","-1")+"],\"origins\":[]}");
        assertEquals(1,mapper.readTree(BrowserSessionState.filter(mapper,state,Set.of("linkedin.com","www.linkedin.com"),"li_at")).path("cookies").size());
    }
}
