package com.ibm.advocacy.collector;

import com.microsoft.playwright.*;
import com.ibm.advocacy.model.Measurement;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DZoneCollectorTest {
    final URI uri=URI.create("https://dzone.com/articles/agents-md-java");
    @Test void expandedCountsPreserveExactVersusApproximateEvidence() throws Exception {
        assertEquals("4900",DZoneCollector.counter("4.9K Views").value());assertTrue(DZoneCollector.counter("4.9K Views").approximate());
        assertEquals("1200000",DZoneCollector.counter("1.2m Views").value());assertEquals("2000000000",DZoneCollector.counter("2B Views").value());
        assertEquals("4963",DZoneCollector.counter("4,963 Views").value());assertFalse(DZoneCollector.counter("4,963 Views").approximate());
        assertEquals("0",DZoneCollector.counter("0 Views").value());assertEquals("4900",DZoneCollector.counter("4.9K\u00a0Views").value());
        for(String invalid:List.of("Likes (4900)","4.9 Views","-1 Views","1,23 Views","NaN Views","Views","1e3 Views","0.0001K Views"))assertThrows(IOException.class,()->DZoneCollector.counter(invalid),invalid);
    }
    @Test void targetValidationRejectsLookalikeHostsNonArticlesAndUnsafeSchemes() {
        assertTrue(DZoneCollector.article(uri));assertTrue(DZoneCollector.article(URI.create("https://www.dzone.com/articles/another-article/")));
        for(String invalid:List.of("https://dzone.com.evil.test/articles/foo","https://evil.dzone.com/articles/foo","http://dzone.com/articles/foo","file:///articles/foo","https://u:p@dzone.com/articles/foo","https://dzone.com:443/articles/foo","https://dzone.com/users/foo","https://dzone.com/articles/../users/foo"))assertFalse(DZoneCollector.article(URI.create(invalid)),invalid);
    }
    Measurement browser(String html,int status) throws Exception {
        try(Playwright playwright=Playwright.create();Browser chrome=playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));BrowserContext context=chrome.newContext()) {
            context.route("**/*",route->route.fulfill(new Route.FulfillOptions().setStatus(status).setContentType("text/html").setBody(html)));
            return new DZoneCollector().read(context.newPage(),uri);
        }
    }
    @Test void readsOnlyTheArticleCounterAndRecordsAutomaticEvidence() throws Exception {
        Measurement measurement=browser("<h1>Article</h1><div>999K Views</div><div id='activity-view-container'><span class='view-count'>4.9K Views</span></div><div>Likes (4)</div>",200);
        assertEquals("4900",measurement.value());assertEquals("VIEWS",measurement.type());assertEquals(uri.toString(),measurement.url());assertFalse(measurement.manual());assertNotNull(Instant.parse(measurement.collectedAt()));assertTrue(measurement.evidence().contains("approximate"));assertTrue(measurement.evidence().contains("4.9K Views"));
    }
    @Test void exactPublicTooltipTakesPriorityOverAbbreviatedDisplay() throws Exception {
        Measurement measurement=browser("<h1>Article</h1><div id='activity-view-container'><span class='view-count' title='4,963 Views'>4.9K Views</span></div>",200);
        assertEquals("4963",measurement.value());assertTrue(measurement.evidence().contains("exact public count"));assertFalse(measurement.evidence().contains("approximate"));
    }
    @Test void blockedPagesAndInvalidCountersNeverProduceMeasurements() throws Exception {
        IOException blocked=assertThrows(IOException.class,()->browser("<h1>Sorry, you have been blocked</h1><span>4.9K Views</span>",403));assertTrue(blocked.getMessage().contains("HTTP 403"));
        assertThrows(IOException.class,()->browser("<h1>Article</h1><div id='activity-view-container'><span class='view-count'>Likes (4)</span></div>",200));
    }
    @Test void dispatcherUsesDZoneAndRetainsGlobalCollectionSwitch() throws Exception {
        MetricCollectors dispatcher=new MetricCollectors();dispatcher.enabled=true;
        dispatcher.dzone=new DZoneCollector(){@Override public Measurement collect(URI target){assertEquals(uri,target);return new Measurement("4900","VIEWS",target.toString(),Instant.now().toString(),"Approximate DZone display",false);}};
        assertEquals("4900",dispatcher.collect("http://dzone.com/articles/agents-md-java").value());
        dispatcher.enabled=false;assertThrows(IOException.class,()->dispatcher.collect(uri.toString()));
    }
}
