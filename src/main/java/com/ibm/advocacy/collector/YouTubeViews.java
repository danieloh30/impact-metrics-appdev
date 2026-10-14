package com.ibm.advocacy.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Read only the requested video's lifetime counter, never recommendation or live concurrency counts. */
final class YouTubeViews {
    private YouTubeViews() {}
    static String videoId(URI uri) throws IOException {
        String id=null,path=uri.getPath();
        if(uri.getHost().equalsIgnoreCase("youtu.be"))id=path.replaceFirst("^/","");
        else if(path.equals("/watch")) {
            for(String parameter:Objects.toString(uri.getRawQuery(),"").split("&")) {
                String[] pair=parameter.split("=",2);
                if(URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals("v")) {
                    if(id!=null)throw new IOException("YouTube URL has duplicate video IDs");
                    id=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";
                }
            }
        } else if(path.matches("/(live|shorts|embed)/[^/]+/?"))id=path.split("/")[2];
        if(id==null||!id.matches("[A-Za-z0-9_-]{11}"))throw new IOException("YouTube URL must identify one video");
        return id;
    }
    static String count(String value,boolean display) throws IOException {
        String text=value.replace('\u00a0',' ').trim();
        String pattern=display?"((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)) views?":"((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+))";
        Matcher match=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(text);
        if(!match.matches())throw new IOException("YouTube exact lifetime Views counter unavailable; retry or enter verified Video Views manually");
        return new java.math.BigInteger(match.group(1).replace(",","")).toString();
    }
    static JsonNode initial(ObjectMapper mapper,String html,String name) throws IOException {
        Matcher marker=Pattern.compile("(?:var\\s+"+name+"|window\\[\\\""+name+"\\\"\\])\\s*=\\s*(?=\\{)").matcher(html);
        if(!marker.find())return mapper.missingNode();
        try(var parser=mapper.getFactory().createParser(html.substring(marker.end()))) {return mapper.readTree(parser);}
    }
    static String read(ObjectMapper mapper,String html,String id) throws IOException {
        JsonNode player=initial(mapper,html,"ytInitialPlayerResponse"),details=player.path("videoDetails");
        if(!details.isMissingNode()) {
            if(!id.equals(details.path("videoId").asText()))throw new IOException("YouTube returned a different video");
            if(details.has("viewCount"))return count(details.path("viewCount").asText(),false);
        }
        JsonNode data=initial(mapper,html,"ytInitialData");
        if(!id.equals(data.path("currentVideoEndpoint").path("watchEndpoint").path("videoId").asText()))throw new IOException("YouTube requested video counter unavailable; retry or enter verified Video Views manually");
        List<String> counts=new ArrayList<>();
        for(JsonNode item:data.path("contents").path("twoColumnWatchNextResults").path("results").path("results").path("contents")) {
            JsonNode renderer=item.path("videoPrimaryInfoRenderer").path("viewCount").path("videoViewCountRenderer");
            if(renderer.isMissingNode())continue;
            if(renderer.path("isLive").asBoolean(false))throw new IOException("YouTube shows live concurrent viewers; lifetime Video Views unavailable");
            JsonNode text=renderer.path("viewCount");
            String label=text.path("simpleText").asText();
            if(label.isEmpty()) {StringBuilder joined=new StringBuilder();for(JsonNode run:text.path("runs"))joined.append(run.path("text").asText());label=joined.toString();}
            counts.add(count(label,true));
        }
        if(counts.size()!=1)throw new IOException("YouTube exact requested-video Views counter unavailable; retry or enter verified Video Views manually");
        return counts.getFirst();
    }
}
