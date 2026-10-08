package com.ibm.advocacy.model;

public record Approval(int phase,int version,String digest,String at,boolean acknowledgedIssues) {}
