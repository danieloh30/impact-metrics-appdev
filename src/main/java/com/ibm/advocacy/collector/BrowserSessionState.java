package com.ibm.advocacy.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.BadRequestException;
import java.net.URI;
import java.time.Instant;
import java.util.Set;

/** Accept only the provider's storage, never the helper's OpenShift login. */
public final class BrowserSessionState {
    private BrowserSessionState() {}
    public static String filter(ObjectMapper mapper, JsonNode state, Set<String> hosts, String requiredCookie) {
        if(state==null||!state.isObject()||!state.path("cookies").isArray()||!state.path("origins").isArray())throw new BadRequestException("Invalid browser session state");
        if(state.toString().length()>2_000_000)throw new BadRequestException("Browser session exceeds 2 MB");
        ObjectNode result=mapper.createObjectNode();var cookies=result.putArray("cookies");var origins=result.putArray("origins");
        boolean authenticated=false;
        for(JsonNode cookie:state.path("cookies")) {
            String domain=cookie.path("domain").asText("").replaceFirst("^\\.","");
            // SharePoint uses one root rtFa cookie for session continuity across sites.
            // Keep only this named root cookie; IBM-host FedAuth is still mandatory.
            boolean rootRefresh=requiredCookie.equals("FedAuth")&&hosts.stream().anyMatch(host->host.endsWith(".sharepoint.com"))&&domain.equals("sharepoint.com")&&cookie.path("name").asText().equals("rtFa")&&cookie.path("path").asText().equals("/");
            if(!hosts.contains(domain)&&!rootRefresh)continue;
            if(!cookie.path("name").isTextual()||!cookie.path("value").isTextual()||!cookie.path("path").asText("").startsWith("/"))throw new BadRequestException("Invalid provider cookie");
            double expires=cookie.path("expires").asDouble(-1);
            if(expires>0&&expires<=Instant.now().getEpochSecond())continue;
            cookies.add(cookie.deepCopy());
            if(hosts.contains(domain)&&cookie.path("name").asText().equals(requiredCookie)&&!cookie.path("value").asText().isBlank())authenticated=true;
        }
        for(JsonNode origin:state.path("origins")) {
            try {URI uri=URI.create(origin.path("origin").asText());
                if("https".equals(uri.getScheme())&&hosts.contains(uri.getHost())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&"".equals(uri.getRawPath())&&uri.getQuery()==null&&uri.getFragment()==null)origins.add(origin.deepCopy());
            }catch(IllegalArgumentException ignored) {}
        }
        if(!authenticated)throw new BadRequestException("Provider sign-in was not completed or has expired. Run the local sign-in helper again.");
        return result.toString();
    }
}
