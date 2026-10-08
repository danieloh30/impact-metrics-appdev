package com.ibm.advocacy.csv;

import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Row;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

@ApplicationScoped
public class CsvCodec {
    public Data read(byte[] bytes,boolean trailing) throws IOException {
        Charset charset=trailing?Charset.forName("windows-1252"):StandardCharsets.UTF_8;
        String text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if(text.startsWith("\ufeff"))text=text.substring(1);
        try(CSVParser parser=CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get().parse(new StringReader(text))) {
            List<CSVRecord> records=parser.getRecords();
            if(records.isEmpty())throw new IllegalArgumentException("CSV is empty");
            List<String> headers=new ArrayList<>();Set<String> seen=new HashSet<>();
            CSVRecord first=records.getFirst();
            int requiredWidth=0;
            for(int i=0;i<first.size();i++) {
                String h=first.get(i).replace('\u00a0',' ').strip();
                if(!h.isBlank())requiredWidth=i+1;
                if(h.isBlank())h="Unnamed column "+(i+1);
                if(!seen.add(h))throw new IllegalArgumentException("Duplicate normalized header: "+h);
                headers.add(h);
            }
            List<Row> rows=new ArrayList<>();
            for(int n=1;n<records.size();n++) {
                CSVRecord record=records.get(n);
                boolean blank=true;for(String value:record)if(!value.isBlank())blank=false;
                if(blank)continue;
                if(record.size()>headers.size()||record.size()<requiredWidth)throw new IllegalArgumentException("CSV record "+(n+1)+" has a different number of values than its named headers");
                Row row=new Row((trailing?"t":"c")+n,Map.of());
                for(int i=0;i<headers.size();i++)row.cells.put(headers.get(i),i<record.size()?record.get(i):"");
                rows.add(row);
            }
            for(String h:new ArrayList<>(headers))if(h.startsWith("Unnamed column ")&&rows.stream().allMatch(r->r.get(h).isBlank())) { headers.remove(h);rows.forEach(r->r.cells.remove(h)); }
            if(!headers.contains("Type of activity"))throw new IllegalArgumentException("Missing Type of activity column");
            if(!headers.contains(URL))throw new IllegalArgumentException("Missing "+URL+" column");
            if(trailing&&!headers.contains("Current total"))throw new IllegalArgumentException("Trailing input requires Current total");
            if(!trailing&&!(headers.contains("Author of activity")||headers.contains("Author")))throw new IllegalArgumentException("Missing Author of activity or Author column");
            return new Data(headers,rows);
        }
    }
    public byte[] write(Data data) throws IOException {
        StringWriter out=new StringWriter();
        try(CSVPrinter printer=new CSVPrinter(out,CSVFormat.RFC4180)) {
            printer.printRecord(data.headers);
            for(Row row:data.rows)printer.printRecord(data.headers.stream().map(row::get).toList());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    public Data output(Data source,List<Row> rows) {
        LinkedHashSet<String> headers=new LinkedHashSet<>(source.headers);
        headers.addAll(List.of(NOTE,"Attendance hours","total_reactions","total_comments","total_reposts","reactions_scraped_at"));
        rows.forEach(r->headers.addAll(r.cells.keySet()));
        return new Data(new ArrayList<>(headers),rows);
    }
}
