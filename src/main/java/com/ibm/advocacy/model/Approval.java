package com.ibm.advocacy.model;

public record Approval(int phase,int version,String digest,String at,boolean acknowledgedIssues,boolean automatic) {
    public Approval(int phase,int version,String digest,String at,boolean acknowledgedIssues){this(phase,version,digest,at,acknowledgedIssues,false);}
}
