package com.ibm.advocacy.model;

import java.util.List;

public record WorkbookRequest(byte[] bytes, String month, String currentSheet, List<String> historySheets, byte[] historyBytes) {
    public WorkbookRequest(byte[] bytes,String month,String currentSheet,List<String> historySheets) { this(bytes,month,currentSheet,historySheets,bytes); }
}
