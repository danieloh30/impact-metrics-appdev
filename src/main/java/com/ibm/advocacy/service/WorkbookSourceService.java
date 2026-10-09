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
    public synchronized Run create(String currentToken,String historyToken,String month,String currentSheet) throws Exception {
        cleanup();Snapshot current=snapshots.get(currentToken),history=snapshots.get(historyToken);
        if(current==null||history==null)throw new ClientErrorException("Workbook snapshot expired. Read both SharePoint workbooks again before preparing inputs.",410);
        if(currentToken.equals(historyToken)||current.workbook().sourceUrl().equals(history.workbook().sourceUrl()))throw new jakarta.ws.rs.BadRequestException("Read two separate workbooks: AvocadoBot for current activities and AppDev impact metrics for history.");
        var source=current.workbook();var past=history.workbook();
        List<String> historySheets=workbooks.historySheets(past.bytes(),month);
        Run run=runs.createSharePointWorkbooks(source.bytes(),source.filename(),past.bytes(),past.filename(),month,currentSheet,historySheets,source.sourceUrl(),past.sourceUrl());
        snapshots.remove(currentToken);snapshots.remove(historyToken);return run;
    }
    private void cleanup(){snapshots.values().removeIf(s->!s.expires().isAfter(Instant.now()));}
}
