package com.ibm.advocacy.collector;
import com.microsoft.playwright.*;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DZoneBatchTest {
 final URI first=URI.create("https://dzone.com/articles/first"),second=URI.create("https://dzone.com/articles/second");
 static class SyntheticCollector extends DZoneCollector {
  int launches;Browser browser;
  @Override Browser launch(Playwright playwright) {assertTrue(backgroundMode());launches++;browser=super.launch(playwright);return browser;}
  @Override void restrict(BrowserContext context) {
   context.route("**/*",route->{boolean blocked=route.request().url().endsWith("/blocked");route.fulfill(new Route.FulfillOptions().setStatus(blocked?403:200).setContentType("text/html").setBody(blocked?"<h1>Blocked</h1>":"<h1>Article</h1><div id='activity-view-container'><span class='view-count'>3.7K Views</span></div>"));});
  }
 }
 @Test void backgroundBatchIsLazyReusesOneBrowserAndClosesAtScopeEnd() throws Exception {
  SyntheticCollector collector=new SyntheticCollector();assertTrue(collector.backgroundMode());
  try(var empty=collector.batch()) {assertEquals(0,collector.launches);}
  try(var batch=collector.batch()) {
   assertThrows(IllegalStateException.class,collector::batch);
   assertEquals("3700",collector.collect(first).value());assertEquals("3700",collector.collect(second).value());assertEquals(1,collector.launches);assertTrue(collector.browser.isConnected());
  }
  assertFalse(collector.browser.isConnected());
  try(var next=collector.batch()) {assertEquals("3700",collector.collect(first).value());assertEquals(2,collector.launches);}
  assertFalse(collector.browser.isConnected());
  assertThrows(IOException.class,()->collector.collect(URI.create("https://example.com/articles/first")));assertEquals(2,collector.launches);
 }
 @Test void blockedArticleDoesNotLaunchVisibleFallbackAndNextArticleUsesTheSameBrowser() throws Exception {
  SyntheticCollector collector=new SyntheticCollector();
  try(var batch=collector.batch()) {
   IOException failure=assertThrows(IOException.class,()->collector.collect(URI.create("https://dzone.com/articles/blocked")));
   assertTrue(failure.getMessage().contains("background collection"));assertTrue(failure.getMessage().contains("HTTP 403"));assertTrue(failure.getMessage().contains("No visible browser fallback"));
   assertEquals("3700",collector.collect(first).value());assertEquals(1,collector.launches);
  }
  assertFalse(collector.browser.isConnected());
 }
 @Test void launchFailureClosesPartialSessionAndDoesNotRelaunchForEveryArticle() throws Exception {
  SyntheticCollector collector=new SyntheticCollector(){@Override Browser launch(Playwright playwright){launches++;throw new PlaywrightException("Synthetic browser launch failure");}};
  try(var batch=collector.batch()) {
   assertThrows(IOException.class,()->collector.collect(first));assertThrows(IOException.class,()->collector.collect(second));assertEquals(1,collector.launches);
  }
  try(var batch=collector.batch()) {assertThrows(IOException.class,()->collector.collect(first));assertEquals(2,collector.launches);}
 }
}
