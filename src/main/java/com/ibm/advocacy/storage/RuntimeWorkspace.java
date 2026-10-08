package com.ibm.advocacy.storage;

import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Run;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

@ApplicationScoped
public class RuntimeWorkspace {
    @ConfigProperty(name="metrics.runtime-root") String configuredRoot;
    @Inject ObjectMapper mapper;
    public Path root() throws IOException {
        Path root=Path.of(configuredRoot).toAbsolutePath().normalize();
        if(!root.getFileName().toString().equals("runtimes")&&!root.getFileName().toString().equals("test-runtimes"))throw new IllegalStateException("Runtime root must be a runtimes directory");
        if(Files.isSymbolicLink(root))throw new IllegalArgumentException("Runtime root cannot be a symlink");
        Files.createDirectories(root);return root.toRealPath();
    }
    public Path path(String relative) throws IOException {
        Path root=root(), path=root.resolve(relative).normalize();
        if(!path.startsWith(root))throw new IllegalArgumentException("Path escapes runtimes");
        Path check=root;for(Path component:root.relativize(path)) { check=check.resolve(component);if(Files.isSymbolicLink(check))throw new IllegalArgumentException("Symlinks are not allowed in runtimes"); }
        return path;
    }
    public Path runPath(String id,String relative) throws IOException {
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid run ID");
        return path("runs/"+id+"/"+relative);
    }
    public void bytes(Path path,byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary=Files.createTempFile(path.getParent(),".write-",".tmp");
        try { Files.write(temporary,bytes);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    public void save(Run run) throws IOException { bytes(runPath(run.id,"state/run.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run)); }
    public Run load(String id) throws IOException {
        Path file=runPath(id,"state/run.json");if(!Files.exists(file))throw new jakarta.ws.rs.NotFoundException("Run not found");
        return mapper.readValue(Files.readAllBytes(file),Run.class);
    }
    public Data data(String id,String name) throws IOException { return mapper.readValue(Files.readAllBytes(runPath(id,"state/"+name+".json")),Data.class); }
    public void data(String id,String name,Data data) throws IOException { bytes(runPath(id,"state/"+name+".json"),mapper.writeValueAsBytes(data)); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
