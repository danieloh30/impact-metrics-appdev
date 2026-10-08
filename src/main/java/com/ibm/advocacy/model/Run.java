package com.ibm.advocacy.model;

import java.util.*;

public class Run {
    public String id,createdAt,status="READY",error="",instructionHash="",inputHash="",review="";
    public Map<String,Object> workbook=new LinkedHashMap<>();
    public int phase=0,version=0;
    public boolean trailing=false,contractAccepted=false;
    public int currentRows,trailingRows;
    public List<Issue> issues=new ArrayList<>();
    public List<Event> events=new ArrayList<>();
    public List<Approval> approvals=new ArrayList<>();
    public LinkedHashMap<String,Artifact> artifacts=new LinkedHashMap<>();
    public LinkedHashMap<String,Measurement> measurements=new LinkedHashMap<>();
}
