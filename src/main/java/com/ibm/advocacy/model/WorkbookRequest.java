package com.ibm.advocacy.model;

import java.util.List;

public record WorkbookRequest(byte[] bytes, String month, String currentSheet, List<String> historySheets) {}
