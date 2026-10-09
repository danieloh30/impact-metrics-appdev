package com.ibm.advocacy.model;

import java.util.*;

public class Data {
    public List<String> headers=new ArrayList<>();
    public List<Row> rows=new ArrayList<>();
    /** Preserve source columns and append result/provenance fields without CSV conversion. */
    public static Data output(Data source,List<Row> rows) {
        LinkedHashSet<String> headers=new LinkedHashSet<>(source.headers);
        headers.addAll(List.of(MetricsSchema.NOTE,"Attendance hours","total_reactions","total_comments","total_reposts","reactions_scraped_at"));
        rows.forEach(r->headers.addAll(r.cells.keySet()));
        return new Data(new ArrayList<>(headers),rows);
    }
    public Data() {}
    public Data(List<String> headers,List<Row> rows) { this.headers=new ArrayList<>(headers);this.rows=rows; }
}
