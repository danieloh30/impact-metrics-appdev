package com.ibm.advocacy.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.model.Artifact;
import com.ibm.advocacy.model.Data;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.ibm.advocacy.model.MetricsSchema.OUTPUTS;

/** Immutable structured results. CSV serialization belongs only to download requests. */
@ApplicationScoped
public class ResultStore {
    @Inject RuntimeWorkspace workspace;
    @Inject ObjectMapper mapper;
    @Inject CsvCodec legacyCsv;

    public static String datasetName(String exportName) {
        if(!OUTPUTS.contains(exportName))throw new BadRequestException("Unknown artifact");
        return exportName.substring(0,exportName.length()-4);
    }
    public byte[] encode(Data data) throws java.io.IOException { return mapper.writeValueAsBytes(data); }
    public Artifact save(String id,String name,int version,int phase,Data data) throws java.io.IOException {
        byte[] bytes=encode(data);
        workspace.bytes(workspace.runPath(id,"output/"+version+"/"+datasetName(name)+".json"),bytes);
        return new Artifact(name,version,phase,data.rows.size(),RuntimeWorkspace.hash(bytes));
    }
    public Data load(String id,String name,int version) throws java.io.IOException {
        Path structured=workspace.runPath(id,"output/"+version+"/"+datasetName(name)+".json");
        if(Files.exists(structured))return mapper.readValue(Files.readAllBytes(structured),Data.class);
        // Read-only compatibility for runs created before structured result storage.
        Path legacy=workspace.runPath(id,"output/"+version+"/"+name);
        if(Files.exists(legacy))return legacyCsv.read(Files.readAllBytes(legacy),false);
        throw new NotFoundException("Artifact does not exist in this version");
    }
}
