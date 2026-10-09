package com.ibm.advocacy.model;

import java.util.List;
import java.util.Map;

public record PreparedWorkbook(Data current, Data trailing, String month, String windowStart,
                               String windowEnd, List<String> historySheets, int missingBaselines, Map<String,Integer> blogRows, Map<String,Integer> blogSkippedRows) {}
