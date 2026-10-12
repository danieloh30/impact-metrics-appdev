package com.ibm.advocacy.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.advocacy.collector.SharePointCollector;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.service.ImpactTrends;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Published history persists independently of Dev Services and individual runs. */
@ApplicationScoped
public class ChartHistoryStore {
    @Inject RuntimeWorkspace workspace;
    @Inject ObjectMapper mapper;
    @Inject ImpactTrends trends;
    @Inject SharePointCollector collector;
    public record Saved(Map<String,Object> source,ImpactTrends.Snapshot snapshot) {}
    private String key(String url) {return RuntimeWorkspace.hash(collector.fileUri(url).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public synchronized Saved cached(String url) throws Exception {
        var file=workspace.path(".chart-history/sources/"+key(url)+".json");
        return !Files.isRegularFile(file)?null:load(mapper.readTree(Files.readAllBytes(file)).get("cacheId").asText());
    }
    public synchronized Saved read(String url,String endMonth) throws Exception {
        var workbook=collector.download(url);return save(workbook.bytes(),workbook.filename(),workbook.sourceUrl(),url,endMonth);
    }
    public synchronized Saved save(byte[] bytes,String filename,String sourceUrl,String requestedUrl,String endMonth) throws Exception {
        var snapshot=trends.read(bytes,filename,endMonth);String sha=RuntimeWorkspace.hash(bytes);
        String id=RuntimeWorkspace.hash((sha+":"+snapshot.endMonth()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String,Object> source=new LinkedHashMap<>();source.put("filename",filename);source.put("sourceUrl",sourceUrl);source.put("sha256",sha);source.put("cacheId",id);source.put("sheet",ImpactTrends.SHEET);source.put("endMonth",snapshot.endMonth());source.put("importedAt",Instant.now().toString());source.put("storage","Persistent runtime file cache");
        Saved saved=new Saved(Map.copyOf(source),snapshot);
        workspace.bytes(workspace.path(".chart-history/"+id+".xlsx"),bytes);
        workspace.bytes(workspace.path(".chart-history/"+id+".json"),mapper.writeValueAsBytes(saved));
        workspace.bytes(workspace.path(".chart-history/sources/"+key(requestedUrl)+".json"),mapper.writeValueAsBytes(Map.of("cacheId",id)));
        return saved;
    }
    public Saved load(String id) throws Exception {
        if(id==null||!id.matches("[a-f0-9]{64}"))throw new BadRequestException("Invalid saved chart history ID");
        var json=workspace.path(".chart-history/"+id+".json");var xlsx=workspace.path(".chart-history/"+id+".xlsx");
        if(!Files.isRegularFile(json)||!Files.isRegularFile(xlsx))throw new BadRequestException("Saved chart history is unavailable; read the chart workbook again");
        Saved saved=mapper.readValue(Files.readAllBytes(json),Saved.class);
        if(!RuntimeWorkspace.hash(Files.readAllBytes(xlsx)).equals(saved.source().get("sha256")))throw new BadRequestException("Saved chart history checksum changed; read the chart workbook again");
        return saved;
    }
    public void attach(Run run,String id) throws Exception {
        Saved saved=load(id);Map<String,Object> source=new LinkedHashMap<>(saved.source());String filename="chart-history-"+id+".xlsx";
        workspace.bytes(workspace.runPath(run.id,"rawdata/"+filename),Files.readAllBytes(workspace.path(".chart-history/"+id+".xlsx")));
        source.put("snapshotFilename",filename);run.workbook.put("chartHistory",source);
    }
}
