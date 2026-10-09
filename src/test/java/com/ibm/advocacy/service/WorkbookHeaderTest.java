package com.ibm.advocacy.service;

import com.ibm.advocacy.model.WorkbookRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class WorkbookHeaderTest {
    @Inject WorkbookService workbooks;
    void row(Sheet sheet,int n,String... values){var row=sheet.createRow(n);for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}
    byte[] fixture(boolean ambiguous) throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            var bob=book.createSheet("Bob csv");row(bob,0,"Date of activity","Author of activity","Tool","Type of activity","Current total","Link / URL to resource");
            row(bob,1,"2026-12-01","A","X","Recorded video","10","https://example.com/export");
            var current=book.createSheet("Current month inputs");row(current,0," Date  of activity\n","Author OF activity","Tool","TYPE OF ACTIVITY","Current total","Link / URL to public\u00a0resource","Link / URL to content / deck","Link / URL to trip report");
            row(current,1,"2026-10-05","Daniel","Quarkus","Recorded video","10","https://example.com/public","https://example.com/deck","https://example.com/trip");
            if(ambiguous)current.getRow(0).createCell(8).setCellValue("Link / URL to resource");
            var history=book.createSheet("Previous months inputs");row(history,0,"Date of activity","Author of activity (Java Runtime)","Tool (Java Runtime)","Type of activity","Current total","Link / URL to public resource");
            row(history,1,"2026-09-30","A; B","X; Y","Recorded video","80","https://example.com/history");
            row(history,2,"2026-07-31","A","X","Recorded video","10","https://example.com/old");
            row(book.createSheet("Color highlight key"),0,"Legend");
            var hidden=book.createSheet("2025 inputs");row(hidden,0,"Instructions");book.setSheetHidden(book.getSheetIndex(hidden),true);
            book.write(out);return out.toByteArray();
        }
    }
    @Test void inspectionRecognizesRawInputHeadersAndPrefersAuthoritativeSheetsOverAnExport() throws Exception {
        var inspected=workbooks.inspect(fixture(false));
        assertEquals("Current month inputs",inspected.suggestedCurrentSheet());assertEquals("2026-10",inspected.suggestedMonth());
        assertEquals(List.of("Previous months inputs"),inspected.suggestedHistorySheets());
        assertTrue(inspected.sheets().stream().filter(s->s.name().equals("Bob csv")).findFirst().orElseThrow().eligible());
        assertTrue(inspected.sheets().stream().filter(s->s.name().equals("Previous months inputs")).findFirst().orElseThrow().eligible());
        assertFalse(inspected.sheets().stream().filter(s->s.name().equals("Color highlight key")).findFirst().orElseThrow().eligible());
        assertFalse(inspected.sheets().stream().filter(s->s.name().equals("2025 inputs")).findFirst().orElseThrow().eligible());
        assertTrue(workbooks.inspect(fixture(false)).sheets().stream().anyMatch(sheet->sheet.name().equals("Current month inputs")&&sheet.eligible()));
    }
    @Test void normalizedPublicUrlsAndScopedAuthorsToolsReachThePipelineWithoutReplacingOtherLinks() throws Exception {
        var result=workbooks.prepare(new WorkbookRequest(fixture(false),"2026-10","Current month inputs",List.of("Previous months inputs")));
        assertEquals(1,result.current().rows.size());var current=result.current().rows.getFirst();
        assertEquals("Daniel",current.get("Author of activity"));assertEquals("https://example.com/public",current.get("Link / URL to resource"));
        assertEquals("https://example.com/deck",current.get("Link / URL to content / deck"));assertEquals("https://example.com/trip",current.get("Link / URL to trip report"));
        assertEquals(4,result.trailing().rows.size());assertTrue(result.trailing().rows.stream().allMatch(row->row.get("Current total").equals("20")));
        assertEquals(java.util.Set.of("A","B"),result.trailing().rows.stream().map(row->row.get("Author of activity")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("X","Y"),result.trailing().rows.stream().map(row->row.get("Tool")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.trailing().rows.stream().allMatch(row->row.get("Link / URL to resource").equals("https://example.com/history")));
    }
    @Test void overlappingHeaderAliasesAreRejectedRatherThanChoosingOneUrl() throws Exception {
        var error=assertThrows(BadRequestException.class,()->workbooks.prepare(new WorkbookRequest(fixture(true),"2026-10","Current month inputs",List.of())));
        assertTrue(error.getMessage().contains("Duplicate header after normalization: Link / URL to resource"));
    }
}
