package com.ibm.advocacy.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Browser profiles contain dangling singleton links and must never become dev-scanned resources. */
class ResourcePackagingTest {
    @Test void mutableRuntimeWorkspaceIsOutsideEveryDevScannedResourceRoot() throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        Document pom=factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());XPath xpath=XPathFactory.newInstance().newXPath();
        NodeList roots=(NodeList)xpath.evaluate("/project/build/resources/resource/directory",pom,XPathConstants.NODESET);
        Path project=Path.of("").toAbsolutePath().normalize(),runtime=project.resolve("runtimes");
        for(int i=0;i<roots.getLength();i++){
            Path resource=project.resolve(roots.item(i).getTextContent().replace("${project.basedir}",project.toString())).normalize();
            assertFalse(runtime.startsWith(resource)||resource.startsWith(runtime),"Mutable runtime workspace overlaps dev-scanned resources: "+resource);
        }
    }
    @Test void generatedInstructionResourcesHaveExactBytesAndNoRuntimeState() throws Exception {
        Path packaged=Path.of("target/classes/metrics-instructions");
        try(var files=Files.walk(packaged)) {assertEquals(Set.of("RUN.md","AGENTS.md"),files.filter(Files::isRegularFile).map(path->packaged.relativize(path).toString()).collect(java.util.stream.Collectors.toSet()));}
        for(String name:List.of("RUN.md","AGENTS.md"))assertArrayEquals(Files.readAllBytes(Path.of("runtimes",name)),Files.readAllBytes(packaged.resolve(name)));
    }
}
