package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Row;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Monthly metrics sheets include non-trailing, already attributed activities as well as refreshable content. */
public final class TrailingEligibility {
    private TrailingEligibility() {}
    public static boolean includes(Row row) {
        // Legacy prepared inputs without this column retain their explicit caller-selected scope.
        if(!row.cells.containsKey("Trailing 90-day"))return true;
        String flag=row.get("Trailing 90-day").strip().toLowerCase(Locale.ROOT);
        return flag.isBlank()?DIGITAL.contains(row.type()):Set.of("yes","true","1").contains(flag);
    }
    public static String baseline(Row row) {
        String value=row.get("Current total");
        return value.startsWith("Missing or invalid baseline — manual review required")?"":value;
    }
}
