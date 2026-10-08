package com.ibm.advocacy.model;

import java.util.List;

public record PreparedWorkbook(Data current, Data trailing, String month, String windowStart,
                               String windowEnd, List<String> historySheets, int missingBaselines) {}
