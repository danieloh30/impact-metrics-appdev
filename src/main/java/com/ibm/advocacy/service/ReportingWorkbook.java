package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.model.Row;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xddf.usermodel.chart.*;
import java.io.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** On-demand Excel export: typed detail, native SUM pivots, and cached summary charts. */
@ApplicationScoped
public class ReportingWorkbook {
    private static final Set<String> NUMBERS=Set.of("Current total","Previous total",VIEWS,"Weight","Impact hours","Exact reporting metric","Exact trailing growth");
    public byte[] write(MonthlyReporting.Report report) throws IOException {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            XSSFSheet overview=book.createSheet("Report");
            line(overview,0,"AppDev advocacy impact",report.month());line(overview,1,"As of",report.cutoff());line(overview,2,"Scope",report.scope());
            line(overview,3,"Included rows",report.included());line(overview,4,"Excluded rows",report.excluded());line(overview,5,"Impact hours",Double.parseDouble(report.impact()));
            line(overview,6,"Model","DA metric model 2026");line(overview,7,"Report state",report.complete()?"Completed agent run":"Agent processing incomplete");
            line(overview,8,"Precision","Impact uses exact allocation before rounding views.");
            line(overview,9,"Charts","Charts reflect this exported report snapshot; regenerate the export after changes.");
            line(overview,10,"Pivots","Native PivotTables refresh when opened in Excel; also use Refresh All after editing detail.");
            for(int i=0;i<report.warnings().size();i++)line(overview,12+i,"Reporting note",report.warnings().get(i));
            overview.setColumnWidth(0,28*256);overview.setColumnWidth(1,85*256);
            details(book,"All reporting rows",report.details());
            List<Row> eligible=report.details().rows.stream().filter(row->row.get("Reporting status").equals("Included")).toList();
            XSSFSheet source=details(book,"Eligible data",new Data(report.details().headers,eligible));
            summaries(book,"Activity summary",report.activity(),false,report.month());summaries(book,"Tool summary",report.tool(),false,report.month());summaries(book,"Bob summary",report.bob(),true,report.month());
            if(!eligible.isEmpty()) {
                pivot(book,"Activity pivot",source,report.details().headers,"Type of activity","Impact hours",false);
                pivot(book,"Tool pivot",source,report.details().headers,"Tool","Impact hours",false);
            }
            List<Row> bob=eligible.stream().filter(row->row.get("Tool").equals("IBM Bob")).toList();
            if(!bob.isEmpty())pivot(book,"IBM Bob pivot",details(book,"Bob eligible data",new Data(report.details().headers,bob)),report.details().headers,"Type of activity","Exact reporting metric",true);
            book.setForceFormulaRecalculation(true);book.write(out);return out.toByteArray();
        }
    }
    private static void line(XSSFSheet sheet,int index,String label,Object value) {
        var row=sheet.createRow(index);row.createCell(0).setCellValue(label);if(value instanceof Number n)row.createCell(1).setCellValue(n.doubleValue());else row.createCell(1).setCellValue(String.valueOf(value));
    }
    private static XSSFSheet details(XSSFWorkbook book,String name,Data data) {
        XSSFSheet sheet=book.createSheet(name);var head=sheet.createRow(0);for(int c=0;c<data.headers.size();c++)head.createCell(c).setCellValue(data.headers.get(c));
        CellStyle numeric=book.createCellStyle();numeric.setDataFormat(book.createDataFormat().getFormat("#,##0.######"));
        for(int r=0;r<data.rows.size();r++) {
            var row=sheet.createRow(r+1);
            for(int c=0;c<data.headers.size();c++) {
                String h=data.headers.get(c),value=data.rows.get(r).get(h);var cell=row.createCell(c);
                if(NUMBERS.contains(h)&&!value.isBlank())try {cell.setCellValue(Calculations.number(value).doubleValue());cell.setCellStyle(numeric);continue;}catch(IllegalArgumentException ignored) {}
                // Text setters never execute a source string beginning with '=' as a formula.
                cell.setCellValue(value);
            }
        }
        sheet.createFreezePane(0,1);for(int c=0;c<data.headers.size();c++)sheet.setColumnWidth(c,24*256);
        if(!data.rows.isEmpty()) {
            XSSFTable table=sheet.createTable(new AreaReference(new CellReference(0,0),new CellReference(data.rows.size(),data.headers.size()-1),SpreadsheetVersion.EXCEL2007));
            String tableName="Report"+name.replaceAll("[^A-Za-z]","");table.setName(tableName);table.setDisplayName(tableName);
        }
        return sheet;
    }
    private static void pivot(XSSFWorkbook book,String name,XSSFSheet source,List<String> headers,String grouping,String measure,boolean bob) {
        XSSFSheet sheet=book.createSheet(name);
        var pivot=sheet.createPivotTable(source.getTables().getFirst(),new CellReference("A3"));
        pivot.addRowLabel(headers.indexOf(grouping));pivot.addColumnLabel(DataConsolidateFunction.SUM,headers.indexOf(measure),bob?"Sum of Views / attendance":"Sum of Impact hours","#,##0.######");
        if(bob)pivot.addReportFilter(headers.indexOf("Tool"));
        pivot.getPivotCacheDefinition().getCTPivotCacheDefinition().setRefreshOnLoad(true);
        sheet.setColumnWidth(0,55*256);sheet.setColumnWidth(1,25*256);
    }
    private static void summaries(XSSFWorkbook book,String name,List<MonthlyReporting.Aggregate> rows,boolean bob,String month) {
        XSSFSheet sheet=book.createSheet(name);String[] headers={"Activity / tool","Current impact hours","Historical growth impact hours","Total impact hours","Allocated metrics","Metric unit"};
        var head=sheet.createRow(0);for(int c=0;c<headers.length;c++)head.createCell(c).setCellValue(headers[c]);
        for(int i=0;i<rows.size();i++) {
            var a=rows.get(i);var row=sheet.createRow(i+1);row.createCell(0).setCellValue(a.name());
            row.createCell(1).setCellValue(Double.parseDouble(a.currentImpact()));row.createCell(2).setCellValue(Double.parseDouble(a.trailingImpact()));row.createCell(3).setCellValue(Double.parseDouble(a.impact()));row.createCell(4).setCellValue(Double.parseDouble(a.metrics()));row.createCell(5).setCellValue(a.unit());
        }
        sheet.createFreezePane(0,1);sheet.setColumnWidth(0,60*256);for(int c=1;c<6;c++)sheet.setColumnWidth(c,28*256);
        if(rows.isEmpty())return;
        var drawing=sheet.createDrawingPatriarch();var anchor=drawing.createAnchor(0,0,0,0,7,0,20,25);var chart=drawing.createChart(anchor);
        chart.setTitleText(name+" — "+month);chart.setTitleOverlay(false);
        var category=chart.createCategoryAxis(AxisPosition.LEFT);category.setTitle(bob?"Activity type":"Activity type / tool");
        var value=chart.createValueAxis(AxisPosition.BOTTOM);value.setTitle(bob?"Allocated metrics (units vary by activity)":"Impact hours");value.setCrosses(AxisCrosses.AUTO_ZERO);category.crossAxis(value);value.crossAxis(category);
        var bars=(XDDFBarChartData)chart.createData(ChartTypes.BAR,category,value);bars.setBarDirection(BarDirection.BAR);bars.setBarGrouping(BarGrouping.CLUSTERED);
        var labels=XDDFDataSourcesFactory.fromStringCellRange(sheet,new CellRangeAddress(1,rows.size(),0,0));
        for(int column:bob?new int[]{4}:new int[]{1,2}) {
            var series=bars.addSeries(labels,XDDFDataSourcesFactory.fromNumericCellRange(sheet,new CellRangeAddress(1,rows.size(),column,column)));series.setTitle(headers[column],null);
        }
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);chart.plot(bars);
    }
}
