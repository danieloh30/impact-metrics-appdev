package com.ibm.advocacy.model;

/** A total assigned to the selected reporting month, separate from when it was entered. */
public record MonthEndValue(String value, String asOf, String note, String recordedAt) {}
