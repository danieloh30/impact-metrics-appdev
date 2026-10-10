package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.URL;

/** Count unique planned resources once, publishing before slow provider calls and after outcomes. */
final class CollectionTracker {
    private final RuntimeWorkspace workspace;
    private final Run run;
    private final Set<String> planned=new HashSet<>(),completed=new HashSet<>();
    private final CollectionProgress progress=new CollectionProgress();
    CollectionTracker(RuntimeWorkspace workspace,Run run,List<Row> rows,boolean backgroundDzone) throws IOException {
        this.workspace=workspace;this.run=run;
        for(Row row:rows) {
            planned.add(Calculations.urlKey(row.get(URL)));
            try {String host=URI.create(row.get(URL).strip()).getHost();if(host!=null&&Set.of("dzone.com","www.dzone.com").contains(host.toLowerCase(Locale.ROOT)))progress.dzoneIncluded=true;}catch(IllegalArgumentException ignored) {}
        }
        progress.phase=run.phase;progress.total=planned.size();progress.backgroundDzone=backgroundDzone;
        progress.startedAt=Instant.now().toString();progress.detail="Preparing resource checks";run.collectionProgress=progress;save();
    }
    private void save() throws IOException {progress.updatedAt=Instant.now().toString();workspace.save(run);}
    private boolean pending(Row row) {String key=Calculations.urlKey(row.get(URL));return planned.contains(key)&&!completed.contains(key);}
    private void resource(Row row) {
        progress.resource=row.get(URL);progress.activity=row.get("Name of activity");
        try {progress.provider=Optional.ofNullable(URI.create(progress.resource.strip()).getHost()).orElse("Resource");}catch(IllegalArgumentException invalid) {progress.provider="Resource";}
        if(Set.of("dzone.com","www.dzone.com").contains(progress.provider.toLowerCase(Locale.ROOT)))progress.provider="DZone";
    }
    void checking(Row row) throws IOException {
        if(!pending(row))return;resource(row);progress.detail="Checking "+progress.provider+" for a fresh measurement";save();
    }
    void success(Row row,Measurement measurement,boolean cached) throws IOException {
        if(!pending(row))return;resource(row);completed.add(Calculations.urlKey(row.get(URL)));progress.completed++;
        if(cached)progress.cached++;else progress.collected++;
        progress.detail=(cached?"Reused saved measurement: ":"Collected: ")+measurement.value()+" "+measurement.type().toLowerCase(Locale.ROOT)+" — "+measurement.evidence();save();
    }
    void failed(Row row,Exception failure) throws IOException {
        if(!pending(row))return;resource(row);completed.add(Calculations.urlKey(row.get(URL)));progress.completed++;progress.failed++;
        progress.detail=Pipeline.message(failure);progress.lastError=progress.detail;save();
    }
    void finish(boolean cancelled) throws IOException {
        progress.state=!cancelled&&progress.completed==progress.total?"COMPLETE":"STOPPED";
        progress.finishedAt=Instant.now().toString();progress.resource="";progress.activity="";progress.provider="";
        progress.detail=progress.state.equals("COMPLETE")?"Resource checks finished; "+progress.failed+" need review":"Collection stopped before all resources were checked";save();
    }
}
