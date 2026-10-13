package com.ibm.advocacy.service;

import com.ibm.advocacy.model.WorkbookRequest;
import com.ibm.advocacy.collector.SharePointFileWriter;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class WorkbookLimitsTest {
    @Inject WorkbookService workbooks;
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    public static byte[] padded(byte[] original) throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(original));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] opaque=new byte[6_100_000];new Random(73).nextBytes(opaque);
            var part=book.getPackage().createPart(PackagingURIHelper.createPartName("/xl/media/synthetic-opaque.bin"),"application/octet-stream");
            try(var stream=part.getOutputStream()){stream.write(opaque);}book.write(out);return out.toByteArray();
        }
    }
    static byte[] zip(int entries,int expandedBytes) throws Exception {
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)){
            for(int i=0;i<entries;i++){zip.putNextEntry(new ZipEntry("entry-"+i));zip.write(new byte[expandedBytes]);zip.closeEntry();}zip.finish();return out.toByteArray();
        }
    }
    @Test void largerThanOldSixMegabyteLimitImportsPreparesAndPreservesOriginal() throws Exception {
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.path(name),Files.readAllBytes(Path.of("runtimes",name)));
        byte[] large=padded(new WorkbookImportTest().fixture(false)),unchanged=large.clone();assertTrue(large.length>6_000_000);assertTrue(large.length<WorkbookLimits.maxBytes());
        assertEquals("October inputs",workbooks.inspect(large).suggestedCurrentSheet());
        var prepared=workbooks.prepare(new WorkbookRequest(large,"2026-10","October inputs",List.of("Previous months")));assertEquals(1,prepared.current().rows.size());assertEquals(6,prepared.trailing().rows.size());
        var run=runs.createWorkbook(large,"Synthetic large workbook.xlsx","2026-10","October inputs",List.of("Previous months"));assertArrayEquals(unchanged,Files.readAllBytes(workspace.runPath(run.id,"rawdata/original.xlsx")));assertArrayEquals(unchanged,large);
    }
    @Test void largerChartHistoryAndMonthlyPublicationKeepCountsAndOpaquePart() throws Exception {
        byte[] chart=padded(new ImpactTrendsTest().fixture());var timeline=new ImpactTrends().read(chart,"Synthetic metrics2026.xlsx",null);assertEquals("100",timeline.channel().series().getFirst().values().getFirst());
        var fixture=new MonthlyInputWorkbookTest();byte[] original=padded(fixture.fixture(false)),before=original.clone();
        var result=fixture.writer.merge(original,fixture.data(fixture.row("1","Existing","200")),"2026-09","CURRENT_INPUTS","synthetic");assertTrue(result.bytes().length>6_000_000);
        try(var book=WorkbookLimits.open(result.bytes(),"Updated workbook")){assertEquals(200,book.getSheet("September inputs").getRow(1).getCell(6).getNumericCellValue());assertNotNull(book.getPackage().getPart(PackagingURIHelper.createPartName("/xl/media/synthetic-opaque.bin")));}
        assertArrayEquals(before,original);
    }
    @Test void exactCompressedBoundaryAcceptedAndOversizeIsActionable413BeforeAnyPublicationCall() {
        int limit=Math.toIntExact(WorkbookLimits.maxBytes());WorkbookLimits.checkSize(new byte[limit],"Synthetic workbook");byte[] oversized=new byte[limit+1];
        var failure=assertThrows(WebApplicationException.class,()->WorkbookLimits.checkSize(oversized,"Synthetic workbook"));assertEquals(413,failure.getResponse().getStatus());assertTrue(failure.getMessage().contains("25.0 MB"));assertTrue(failure.getMessage().contains("25000000 bytes"));assertTrue(failure.getMessage().contains("Remove unused"));assertFalse(failure.getMessage().contains("METRICS_"));
        // A null request context deliberately fails if any network operation precedes the guard.
        var publish=assertThrows(WebApplicationException.class,()->new SharePointFileWriter(null).replace(URI.create("http://127.0.0.1/sites/test/file.xlsx"),"unused",oversized));assertEquals(413,publish.getResponse().getStatus());
    }
    @Test void malformedArchivesAndTooManyEntriesAreRejected() throws Exception {
        assertThrows(BadRequestException.class,()->WorkbookLimits.checkArchive("not a zip".getBytes(),"Workbook"));assertThrows(BadRequestException.class,()->WorkbookLimits.open(zip(1,10),"Workbook"));
        var failure=assertThrows(BadRequestException.class,()->WorkbookLimits.checkArchive(zip(4001,0),"Workbook"));assertTrue(failure.getMessage().contains("4,000 ZIP entries"));
    }
    @Test void workbookSheetLimitIsEnforced() throws Exception {
        byte[] bytes;try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){for(int i=0;i<101;i++)book.createSheet("Sheet"+i);book.write(out);bytes=out.toByteArray();}
        var failure=assertThrows(BadRequestException.class,()->WorkbookLimits.open(bytes,"Workbook"));assertTrue(failure.getMessage().contains("100 sheets"));
    }
    @Test void poiZipBombProtectionsRemainEnabledAfterArchivePreflight() throws Exception {
        byte[] original=new WorkbookImportTest().fixture(false),bomb;
        try(var input=new ZipInputStream(new ByteArrayInputStream(original));var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)){
            ZipEntry entry;while((entry=input.getNextEntry())!=null){zip.putNextEntry(new ZipEntry(entry.getName()));zip.write(input.readAllBytes());if(entry.getName().equals("xl/worksheets/sheet1.xml")){zip.write("<!--".getBytes());byte[] comment=new byte[200_000];Arrays.fill(comment,(byte)'a');zip.write(comment);zip.write("-->".getBytes());}zip.closeEntry();}zip.finish();bomb=out.toByteArray();
        }
        double protection=ZipSecureFile.getMinInflateRatio();WorkbookLimits.checkArchive(bomb,"Workbook");assertThrows(BadRequestException.class,()->WorkbookLimits.open(bomb,"Workbook"));assertEquals(protection,ZipSecureFile.getMinInflateRatio());assertTrue(protection>0);
    }
}
