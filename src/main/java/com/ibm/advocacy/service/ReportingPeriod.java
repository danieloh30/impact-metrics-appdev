package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Run;
import java.time.*;

/** A run's reporting date is frozen; reopening it never turns interim data into a final snapshot. */
public final class ReportingPeriod {
    private ReportingPeriod() {}
    public static LocalDate cutoff(YearMonth month,LocalDate date) {
        return date.isBefore(month.atEndOfMonth())?date:month.atEndOfMonth();
    }
    public static LocalDate runDate(Run run) {
        Object saved=run.workbook.get("asOf");
        if(saved!=null)return LocalDate.parse(String.valueOf(saved));
        if(run.createdAt!=null&&!run.createdAt.isBlank())return Instant.parse(run.createdAt).atZone(ZoneId.systemDefault()).toLocalDate();
        return LocalDate.now();
    }
    public static String cutoff(Run run) {
        String month=String.valueOf(run.workbook.getOrDefault("month",""));
        return month.isBlank()?"":cutoff(YearMonth.parse(month),runDate(run)).toString();
    }
    public static boolean provisional(Run run) {
        String month=String.valueOf(run.workbook.getOrDefault("month",""));
        return !month.isBlank()&&runDate(run).isBefore(YearMonth.parse(month).atEndOfMonth());
    }
    public static String publicationStage(Run run) {return provisional(run)?"PROVISIONAL":"COMPLETED";}
}
