package com.ibm.advocacy.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class YouTubeViewsTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String id="U1eaMeXd4QQ";
    String player(String count) {return "<script>var ytInitialPlayerResponse = {\"videoDetails\":{\"videoId\":\""+id+"\",\"viewCount\":\""+count+"\"}};</script>";}
    String page(String label,boolean live) {return "<script>var ytInitialPlayerResponse = {\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}};</script><script>var ytInitialData = {\"currentVideoEndpoint\":{\"watchEndpoint\":{\"videoId\":\""+id+"\"}},\"contents\":{\"twoColumnWatchNextResults\":{\"results\":{\"results\":{\"contents\":[{\"videoPrimaryInfoRenderer\":{\"viewCount\":{\"videoViewCountRenderer\":{\"viewCount\":{\"simpleText\":\""+label+"\"},\"originalViewCount\":\"0\",\"isLive\":"+live+"}}}}]}}}}};</script>";}
    @Test void exactCountersNeverTruncateAtCommasOrDecimals() throws Exception {
        assertEquals("2286",YouTubeViews.count("2,286",false));assertEquals("1645",YouTubeViews.count("1,645 views",true));assertEquals("1000000",YouTubeViews.count("1,000,000 views",true));assertEquals("0",YouTubeViews.count("0 views",true));
        for(String invalid:List.of("2.2K","2,28","2.286","-2","2 watching now","1e3"))assertThrows(IOException.class,()->YouTubeViews.count(invalid,false));
        assertThrows(IOException.class,()->YouTubeViews.count("2.2K views",true));
    }
    @Test void playerCounterIsScopedToTheRequestedVideo() throws Exception {
        assertEquals("2286",YouTubeViews.read(mapper,"<script>var recommendation={\"viewCount\":\"9\"};</script>"+player("2286"),id));
        assertEquals("2286",YouTubeViews.read(mapper,player("2,286"),id));
        assertThrows(IOException.class,()->YouTubeViews.read(mapper,player("2286").replace(id,"OTHERvid123"),id));
    }
    @Test void cloudWatchPageUsesFullMainCounterWhenPlayerRequiresSignIn() throws Exception {
        assertEquals("2286",YouTubeViews.read(mapper,page("2,286 views",false),id));
        assertEquals("1645",YouTubeViews.read(mapper,page("1,645 views",false)+"<script>var extra={\"viewCount\":\"99,999\"};</script>",id));
    }
    @Test void unrelatedAbbreviatedConcurrentAndMissingCountersRequireReview() {
        for(String html:List.of(page("2.2K views",false),page("2 watching now",true),page("2 views",true),page("2,286 views",false).replace(id,"OTHERvid123"),"<script>var recommendation={\"viewCount\":\"2286\"};</script>","<script>var ytInitialData = {broken};</script>"))assertThrows(IOException.class,()->YouTubeViews.read(mapper,html,id));
    }
    @Test void mainCounterSupportsRunsAndWindowAssignments() throws Exception {
        String html=page("2,286 views",false).replace("var ytInitialData","window[\"ytInitialData\"]").replace("\"simpleText\":\"2,286 views\"","\"runs\":[{\"text\":\"2,286\"},{\"text\":\" views\"}]");
        assertEquals("2286",YouTubeViews.read(mapper,html,id));
    }
    @Test void supportedVideoUrlsUseOneCanonicalWatchRequestWithoutLogin() throws Exception {
        MetricCollectors collectors=new MetricCollectors(){@Override String fetch(URI uri){assertEquals("https://www.youtube.com/watch?v="+id+"&hl=en&gl=US",uri.toString());return page("2,286 views",false);}};
        collectors.enabled=true;collectors.mapper=mapper;
        for(String url:List.of("https://youtube.com/watch?v="+id+"&t=20","https://youtu.be/"+id+"?si=share","https://www.youtube.com/live/"+id,"https://m.youtube.com/shorts/"+id,"https://www.youtube.com/embed/"+id)) {
            var measurement=collectors.collect(url);assertEquals("2286",measurement.value());assertFalse(measurement.manual());assertTrue(measurement.evidence().contains(id));assertEquals(url,measurement.url());
        }
        for(String url:List.of("https://youtube.com/watch?v="+id+"&v="+id,"https://youtu.be/"+id+"/other","https://youtube.com/channel/"+id))assertThrows(IOException.class,()->collectors.collect(url));
    }
}
