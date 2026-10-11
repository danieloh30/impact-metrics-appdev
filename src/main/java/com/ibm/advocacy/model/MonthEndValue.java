package com.ibm.advocacy.model;

/** An explicit month-end attestation, kept separate from the time it was entered. */
public record MonthEndValue(String value, String asOf, String note, String recordedAt) {}
