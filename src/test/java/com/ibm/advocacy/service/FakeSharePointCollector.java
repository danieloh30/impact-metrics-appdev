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
    @Override public Workbook download(String url) throws Exception {
        var uri=fileUri(url);downloads.incrementAndGet();
        lastBytes=new WorkbookImportTest().fixture(false);
        return new Workbook(lastBytes,"SharePoint activities.xlsx",uri.toASCIIString());
    }
    @Override public Map<String,Object> session(){return Map.of("enabled",true,"busy",false,"status","CONNECTED","message","Offline authenticated transport fixture");}
    @Override public Map<String,Object> connect(String url){fileUri(url);return session();}
}
