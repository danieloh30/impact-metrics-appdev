package com.ibm.advocacy.service;

import io.quarkus.test.junit.*;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(WorkbookLimitsSmallTest.Profile.class)
class WorkbookLimitsSmallTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides(){return Map.of("metrics.workbook.max-bytes","10000","metrics.workbook.max-expanded-bytes","100000");}
    }
    @Test void customCompressedLimitUsesExactBoundaryAndActionableError(){
        assertEquals(10000,WorkbookLimits.maxBytes());WorkbookLimits.checkSize(new byte[10000],"Workbook");
        var failure=assertThrows(WebApplicationException.class,()->WorkbookLimits.checkSize(new byte[10001],"Workbook"));assertEquals(413,failure.getResponse().getStatus());assertTrue(failure.getMessage().contains("10000 bytes"));
    }
    @Test void actualExpandedStreamingBytesAndAggregateAcrossEntriesEnforceBoundary() throws Exception {
        byte[] exact=WorkbookLimitsTest.zip(2,50000);assertTrue(exact.length<10000);WorkbookLimits.checkArchive(exact,"Workbook");
        var failure=assertThrows(WebApplicationException.class,()->WorkbookLimits.checkArchive(WorkbookLimitsTest.zip(2,50001),"Workbook"));assertEquals(413,failure.getResponse().getStatus());assertTrue(failure.getMessage().contains("100000 bytes"));
    }
    @Test void validSmallWorkbookStillOpensUnderConfiguredLimits() throws Exception {
        try(var workbook=WorkbookLimits.open(new WorkbookImportTest().fixture(false),"Workbook")){assertEquals("October inputs",workbook.getSheetAt(0).getSheetName());}
    }
}
