package com.ibm.advocacy.model;

/** Explicit user decision on supplied historical data, distinct from a fresh lifetime measurement. */
public record InputReview(String views,String duration,String note,boolean edited) {}
