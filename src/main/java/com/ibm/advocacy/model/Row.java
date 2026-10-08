package com.ibm.advocacy.model;

import java.util.*;

public class Row {
    public String id;
    public LinkedHashMap<String,String> cells=new LinkedHashMap<>();
    public Row() {}
    public Row(String id, Map<String,String> cells) { this.id=id; this.cells.putAll(cells); }
    public String get(String key) { return cells.getOrDefault(key,""); }
    public Row copy() { return new Row(id,cells); }
    public String type() { return get("Type of activity").strip().toLowerCase(Locale.ROOT); }
}
