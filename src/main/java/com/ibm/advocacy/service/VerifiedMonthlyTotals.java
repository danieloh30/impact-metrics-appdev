package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import java.math.BigDecimal;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Explicitly reviewed source totals for one reporting month; never inferred from retained views. */
final class VerifiedMonthlyTotals {
    private VerifiedMonthlyTotals() {}
    static boolean matches(Run run,Row row){return !row.get("Verified reporting month").isBlank()&&row.get("Verified reporting month").equals(String.valueOf(run.workbook.getOrDefault("month","")));}
    static void apply(Run run,Row original,Row row){
        String total=original.get("Verified workbook current total").isBlank()?original.get("Current total"):original.get("Verified workbook current total");
        String baseline=original.get("Verified workbook previous total").isBlank()?original.get("Previous total"):original.get("Verified workbook previous total");
        BigDecimal current=Calculations.number(total),previous=Calculations.number(baseline);
        if(current.signum()<0||previous.signum()<0||current.compareTo(previous)<0)throw new IllegalArgumentException("Verified monthly totals require a nonnegative current total and a previous total no greater than current");
        row.cells.put("Current total",Calculations.format(current));row.cells.put("Previous total",Calculations.format(previous));row.cells.put(VIEWS,Calculations.format(current.subtract(previous)));row.cells.put("Metric basis","Trailing delta");
        row.cells.put(NOTE,"Verified workbook totals for "+run.workbook.get("month")+": current "+Calculations.format(current)+" minus previous "+Calculations.format(previous)+". No live collection claimed.");
        row.cells.put("Verified reporting month",original.get("Verified reporting month"));row.cells.put("Activity stage",original.get("Activity stage"));
    }
}
