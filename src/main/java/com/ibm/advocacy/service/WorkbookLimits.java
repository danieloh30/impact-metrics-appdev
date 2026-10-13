package com.ibm.advocacy.service;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.microprofile.config.ConfigProvider;
import java.io.*;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** Shared XLSX limits for imports, chart history and publication. */
public final class WorkbookLimits {
    private WorkbookLimits() {}
    private static long setting(String name,long fallback) {
        long value=ConfigProvider.getConfig().getOptionalValue(name,Long.class).orElse(fallback);
        if(value<=0)throw new IllegalStateException(name+" must be positive");
        return value;
    }
    public static long maxBytes(){return setting("metrics.workbook.max-bytes",25_000_000);}
    private static long maxExpandedBytes(){return setting("metrics.workbook.max-expanded-bytes",100_000_000);}
    private static String size(long bytes){return String.format(Locale.US,"%.1f MB",bytes/1_000_000.0);}
    public static void checkSize(byte[] bytes,String label) {
        if(bytes==null||bytes.length==0)throw new BadRequestException(label+" is empty. Use an unencrypted .xlsx workbook.");
        long limit=maxBytes();
        if(bytes.length>limit)throw new WebApplicationException(label+" is "+size(bytes.length)+" ("+bytes.length+" bytes); the configured limit is "+size(limit)+" ("+limit+" bytes). Remove unused images, sheets or formatting, or increase the configured workbook limit before retrying. Your saved runs are kept.",413);
    }
    /** Count actual expanded bytes, not ZIP header claims, before constructing POI's workbook model. */
    public static void checkArchive(byte[] bytes,String label) {
        checkSize(bytes,label);long limit=maxExpandedBytes(),expanded=0;int entries=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer=new byte[16_384];
            while(zip.getNextEntry()!=null) {
                if(++entries>4_000)throw new BadRequestException(label+" contains more than 4,000 ZIP entries. Remove unused workbook content before retrying.");
                int read;while((read=zip.read(buffer))!=-1) {
                    expanded+=read;
                    if(expanded>limit)throw new WebApplicationException(label+" expands beyond the configured "+size(limit)+" content limit ("+limit+" bytes). Remove unused images, sheets or formatting, or increase the configured expanded-content limit before retrying.",413);
                }
            }
            if(entries==0)throw new BadRequestException("Cannot read "+label.toLowerCase(Locale.ROOT)+". Use an unencrypted .xlsx workbook.");
        }catch(WebApplicationException failure){throw failure;}
        catch(IOException failure){throw new BadRequestException("Cannot read "+label.toLowerCase(Locale.ROOT)+". Use an unencrypted .xlsx workbook.");}
    }
    public static XSSFWorkbook open(byte[] bytes,String label) {
        checkArchive(bytes,label);
        try {
            XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes));
            if(book.getNumberOfSheets()>100){book.close();throw new BadRequestException(label+" exceeds 100 sheets");}
            return book;
        }catch(WebApplicationException failure){throw failure;}
        catch(Exception failure){throw new BadRequestException("Cannot read "+label.toLowerCase(Locale.ROOT)+". Use an unencrypted .xlsx workbook.");}
    }
}
