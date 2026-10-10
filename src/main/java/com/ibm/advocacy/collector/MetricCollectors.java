package com.ibm.advocacy.collector;

import com.ibm.advocacy.model.Measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

@ApplicationScoped
public class MetricCollectors {
    @ConfigProperty(name="metrics.collection.enabled") boolean enabled;
    @Inject ObjectMapper mapper;
    @Inject LinkedInCollector linkedin;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> HOSTS=Set.of("youtube.com","www.youtube.com","m.youtube.com","youtu.be","github.com","www.github.com","api.github.com","registry.terraform.io");
    public static String type(String url) {
        try {
            URI u=URI.create(url.strip());String h=Optional.ofNullable(u.getHost()).orElse("").toLowerCase(Locale.ROOT);
            if(h.equals("linkedin.com")||h.equals("www.linkedin.com"))return u.getPath().startsWith("/events/")?"VIDEO_VIEWS":"REACTIONS";
            if(h.equals("github.com")||h.equals("www.github.com"))return "STARS";
            if(h.equals("registry.terraform.io"))return "DOWNLOADS";
            if(Set.of("youtube.com","www.youtube.com","m.youtube.com","youtu.be").contains(h))return "VIEWS";
        } catch(Exception ignored) {}
        return "VIEWS";
    }
    /** Upgrade public HTTP resources for collection; browser navigation supports either scheme. */
    public static URI resourceUri(String url) throws IOException {
        URI uri;
        try {uri=URI.create(url.strip());}catch(IllegalArgumentException invalid){throw new IOException("Invalid resource URL",invalid);}
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()>0||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())))throw new IOException("Resource URL must use HTTP or HTTPS with a public host");
        return URI.create("https:"+uri.toString().substring(uri.toString().indexOf(':')+1));
    }
    public Measurement collect(String url) throws Exception {

        if(!enabled)throw new IOException("Automatic collection disabled; enter a verified measurement");
        URI uri=resourceUri(url);String host=Optional.ofNullable(uri.getHost()).orElse("").toLowerCase(Locale.ROOT);
        if(Set.of("linkedin.com","www.linkedin.com").contains(host))return linkedin.collect(uri);
        String value,evidence;
        if(Set.of("youtube.com","www.youtube.com","m.youtube.com","youtu.be").contains(host)) {
            if(host.equals("youtu.be"))uri=URI.create("https://www.youtube.com/watch?v="+URLEncoder.encode(uri.getPath().substring(1),java.nio.charset.StandardCharsets.UTF_8));
            String html=fetch(uri);Matcher match=Pattern.compile("\"viewCount\"\\s*:\\s*\"?(\\d+)").matcher(html);
            if(!match.find())throw new IOException("YouTube viewCount unavailable; enter Video Views manually");
            value=match.group(1);evidence="YouTube public page viewCount";
        } else if(host.equals("github.com")||host.equals("www.github.com")) {
            String[] segments=uri.getPath().split("/");if(segments.length<3||!segments[1].matches("[A-Za-z0-9_.-]+")||!segments[2].matches("[A-Za-z0-9_.-]+"))throw new IOException("Invalid GitHub repository URL");
            var data=mapper.readTree(fetch(URI.create("https://api.github.com/repos/"+segments[1]+"/"+segments[2])));
            if(!data.path("stargazers_count").canConvertToLong())throw new IOException("GitHub stars unavailable");
            value=data.path("stargazers_count").asText();evidence="GitHub repository API; forks="+data.path("forks_count")+" watchers="+data.path("watchers_count");
        } else if(host.equals("registry.terraform.io")) {
            String[] segments=uri.getPath().split("/");if(segments.length<4||!segments[1].equals("providers")||!segments[2].matches("[A-Za-z0-9_-]+")||!segments[3].matches("[A-Za-z0-9_-]+"))throw new IOException("Invalid Terraform provider URL");
            var data=mapper.readTree(fetch(URI.create("https://registry.terraform.io/v1/providers/"+segments[2]+"/"+segments[3])));
            if(!data.path("downloads").canConvertToLong())throw new IOException("Registry downloads unavailable");
            value=data.path("downloads").asText();evidence="Terraform Registry provider API downloads";
        } else throw new IOException("No collector for this host; manual collection required");
        return new Measurement(value,type(url),url,Instant.now().toString(),evidence,false);
    }
    String fetch(URI uri) throws Exception {
        for(int redirect=0;redirect<4;redirect++) {
            if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()>0||!HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)))throw new IOException("Collector redirect outside approved hosts");
            for(InetAddress address:InetAddress.getAllByName(uri.getHost()))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())throw new IOException("Private collector address rejected");
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(18)).header("User-Agent","Mozilla/5.0 RUN-metrics/1.0").header("Accept","application/json,text/html").GET().build();
            HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream body=response.body()) {
                if(response.statusCode()>=300&&response.statusCode()<400) { uri=uri.resolve(response.headers().firstValue("location").orElseThrow());continue; }
                if(response.statusCode()!=200)throw new IOException("Provider returned HTTP "+response.statusCode()+"; retry or collect manually");
                byte[] bytes=body.readNBytes(8_000_001);if(bytes.length>8_000_000)throw new IOException("Provider response too large");
                return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Too many provider redirects");
    }
}
