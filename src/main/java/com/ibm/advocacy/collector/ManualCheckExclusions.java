package com.ibm.advocacy.collector;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Sources whose digital metrics are supplied by the workbook, rather than URL collection. */
@ApplicationScoped
public class ManualCheckExclusions {
    private final List<URI> rules;

    @Inject
    public ManualCheckExclusions(@ConfigProperty(name="metrics.collection.manual-check-exclusions") List<String> entries) {
        rules=entries.stream().map(entry->{
            URI rule=URI.create(entry.contains("://")?entry.strip():"https://"+entry.strip());
            if(!publicUrl(rule)||rule.getFragment()!=null)throw new IllegalArgumentException("Invalid manual-check exclusion: "+entry);
            query(rule); // Reject malformed query encoding at startup.
            return rule;
        }).toList();
    }

    public boolean matches(String url) {
        try {
            URI source=URI.create(url.strip());
            if(!publicUrl(source))return false;
            for(URI rule:rules) {
                if(!host(rule).equals(host(source)))continue;
                String path=path(rule),candidate=path(source);
                if(rule.getRawQuery()!=null) {
                    if(!path.equals(candidate))continue;
                    Map<String,List<String>> required=query(rule),actual=query(source);
                    if(required.entrySet().stream().allMatch(entry->entry.getValue().equals(actual.get(entry.getKey()))))return true;
                } else if(path.isEmpty()||candidate.equals(path)||candidate.startsWith(path+"/"))return true;
            }
        } catch(IllegalArgumentException ignored) { /* Malformed resources retain normal collection/review rules. */ }
        return false;
    }

    private static boolean publicUrl(URI uri) {
        return uri.getHost()!=null&&Set.of("http","https").contains(Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT))&&uri.getUserInfo()==null&&uri.getPort()==-1;
    }
    private static String host(URI uri) {
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("www.")?host.substring(4):host;
    }
    private static String path(URI uri) {
        String path=Optional.ofNullable(uri.getPath()).orElse("");
        while(path.endsWith("/"))path=path.substring(0,path.length()-1);
        return path;
    }
    private static Map<String,List<String>> query(URI uri) {
        Map<String,List<String>> values=new LinkedHashMap<>();
        if(uri.getRawQuery()==null)return values;
        for(String field:uri.getRawQuery().split("&")) {
            String[] pair=field.split("=",2);
            String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8);
            values.computeIfAbsent(key,k->new ArrayList<>()).add(value);
        }
        return values;
    }
}
