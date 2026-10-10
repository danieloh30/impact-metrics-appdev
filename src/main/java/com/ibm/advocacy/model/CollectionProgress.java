package com.ibm.advocacy.model;

/** Persisted resource-check progress; failures count as checked, not as verified measurements. */
public class CollectionProgress {
    public int phase,total,completed,collected,cached,failed;
    public boolean dzoneIncluded,backgroundDzone=true;
    public String state="CHECKING",resource="",activity="",provider="",detail="",lastError="",startedAt="",updatedAt="",finishedAt="";
}
