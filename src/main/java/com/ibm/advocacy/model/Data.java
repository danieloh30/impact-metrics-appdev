package com.ibm.advocacy.model;

import java.util.*;

public class Data {
    public List<String> headers=new ArrayList<>();
    public List<Row> rows=new ArrayList<>();
    public Data() {}
    public Data(List<String> headers,List<Row> rows) { this.headers=new ArrayList<>(headers);this.rows=rows; }
}
