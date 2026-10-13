package com.ibm.advocacy.service;

import com.ibm.advocacy.collector.SharePointCollector;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline transport fixture only; never included in the production application. */
@Alternative @Priority(1) @ApplicationScoped
public class FakeSharePointCollector extends SharePointCollector {
    public static final AtomicInteger downloads=new AtomicInteger();
    public static volatile byte[] lastBytes;
    public static final java.util.concurrent.ConcurrentMap<String,byte[]> files=new java.util.concurrent.ConcurrentHashMap<>();
    public static final AtomicInteger writes=new AtomicInteger();
    public static volatile boolean denyWrites;
    @Override public com.ibm.advocacy.collector.SharePointFileWriter.FileStatus fileStatus(String url){fileUri(url);return new com.ibm.advocacy.collector.SharePointFileWriter.FileStatus(2,"","",true,true,"2026-10-12T15:38:01Z","206.0","2026-10-12T17:00:00Z");}
    @Override public Workbook download(String url) throws Exception {
        var uri=fileUri(url);downloads.incrementAndGet();
        byte[] saved=files.get(uri.toASCIIString());if(saved!=null){lastBytes=saved.clone();return new Workbook(lastBytes,"Synthetic metrics.xlsx",uri.toASCIIString());}
        if(uri.getPath().contains("Advocacy RUN metrics")) {lastBytes=new ImpactTrendsTest().fixture();return new Workbook(lastBytes,"Advocacy RUN metrics 2026.xlsx",uri.toASCIIString());}
        lastBytes=uri.getHost().equals("ibm.sharepoint.com")?history():new WorkbookImportTest().fixture(false);
        return new Workbook(lastBytes,uri.getHost().equals("ibm.sharepoint.com")?"AppDev metrics.xlsx":"SharePoint activities.xlsx",uri.toASCIIString());
    }
    static byte[] history() throws Exception {
        var fixture=new WorkbookImportTest();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) {
            for(String month:java.util.List.of("July","August","September","October","November")) {
                var sheet=book.createSheet(month+" inputs");fixture.row(sheet,0,fixture.headers);
                int last=java.time.Month.valueOf(month.toUpperCase()).getValue();int n=1;
                for(int m=7;m<=last;m++) {
                    String title=java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.FULL,java.util.Locale.US);
                    String date=m==7?"2026-07-31":m==8?"2026-08-01":m==9?"2026-09-30":m==10?"2026-10-10":"2026-11-01";
                    fixture.row(sheet,n++,date,"A","X","Recorded video",title+" history","100","https://example.com/"+title,"Yes");
                }
            }
            var august=book.getSheet("August inputs");fixture.row(august,3,"2025-08-01","A","X","Recorded video","Wrong year","1","https://example.com/old","");
            fixture.row(august,4,"2026-07-31","A","X","Recorded video","Outside window","1","https://example.com/outside","");
            var summary=book.createSheet("Previous months");fixture.row(summary,0,fixture.headers);fixture.row(summary,1,"2026-09-01","A","X","Recorded video","Duplicate summary","1","https://example.com/summary","");
            book.write(out);return out.toByteArray();
        }
    }
    @Override public Map<String,Object> session()
{return Map.of("enabled",true,"busy",false,"status","CONNECTED","message","Offline authenticated transport fixture");}
    @Override public Map<String,Object> connect(String url){fileUri(url);return session();}
    @Override public Workbook update(String url,String expectedSha,byte[] candidate) throws Exception {
        var uri=fileUri(url);if(denyWrites)throw new jakarta.ws.rs.ClientErrorException("SharePoint denied edit access",403);
        files.compute(uri.toASCIIString(),(key,before)->{if(before==null||!com.ibm.advocacy.storage.RuntimeWorkspace.hash(before).equals(expectedSha))throw new jakarta.ws.rs.ClientErrorException("Workbook changed after preview",409);writes.incrementAndGet();return candidate.clone();});
        return new Workbook(candidate.clone(),"Synthetic metrics.xlsx",uri.toASCIIString());
    }
}
