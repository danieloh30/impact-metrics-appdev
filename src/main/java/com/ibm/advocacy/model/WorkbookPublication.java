package com.ibm.advocacy.model;

/** Public progress/receipt only. Workbook bytes and authentication remain private to the workspace. */
public class WorkbookPublication {
    public String status="NOT_STARTED",stage="",sheet="",message="",planDigest="",sourceSha256="",resultSha256="",publishedAt="";
    public int version,revision,updated,appended,unchanged,skipped;
    public boolean createdSheet;
}
