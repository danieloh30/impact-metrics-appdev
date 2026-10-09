package com.ibm.advocacy.service;

import com.ibm.advocacy.collector.SharePointCollector;
import com.ibm.advocacy.model.Run;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import java.time.Instant;
import java.util.*;

/** Bounded, expiring snapshots keep inspection and preparation on exactly the same bytes. */
@ApplicationScoped
public class WorkbookSourceService {
    @Inject SharePointCollector sharepoint;
    @Inject WorkbookService workbooks;
    @Inject RunService runs;
    private record Snapshot(SharePointCollector.Workbook workbook,Instant expires) {}
    private final Map<String,Snapshot> snapshots=new LinkedHashMap<>();
    public record SourceInspection(List<WorkbookService.SheetInfo> sheets,String suggestedMonth,String suggestedCurrentSheet,List<String> suggestedHistorySheets,String sourceToken,String filename) {}
    public synchronized SourceInspection inspect(String url) throws Exception {
        cleanup();var workbook=sharepoint.download(url);sharepoint.inspectionStage("INSPECTING");WorkbookService.Inspection inspection;
        try{inspection=workbooks.inspect(workbook.bytes());}catch(Exception e){sharepoint.inspectionStage("FAILED");throw e;}
        if(snapshots.size()>=5)snapshots.remove(snapshots.keySet().iterator().next());
        String token=UUID.randomUUID().toString();snapshots.put(token,new Snapshot(workbook,Instant.now().plusSeconds(1800)));
        sharepoint.inspectionStage("DONE");
        return new SourceInspection(inspection.sheets(),inspection.suggestedMonth(),inspection.suggestedCurrentSheet(),inspection.suggestedHistorySheets(),token,workbook.filename());
    }
    public synchronized Run create(String token,String month,String currentSheet,List<String> historySheets) throws Exception {
        cleanup();Snapshot snapshot=snapshots.get(token);
        if(snapshot==null)throw new ClientErrorException("Workbook snapshot expired. Read SharePoint workbook again before preparing inputs.",410);
        var source=snapshot.workbook();Run run=runs.createSharePointWorkbook(source.bytes(),source.filename(),month,currentSheet,historySheets,source.sourceUrl());
        snapshots.remove(token);return run;
    }
    private void cleanup(){snapshots.values().removeIf(s->!s.expires().isAfter(Instant.now()));}
}
