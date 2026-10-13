package com.ibm.advocacy.service;

import org.apache.poi.ss.usermodel.*;
import jakarta.ws.rs.BadRequestException;
import java.math.BigDecimal;
import java.util.*;

/** Numeric Excel cells use their stored value, never locale/display formatting. */
final class WorkbookCells {
    private WorkbookCells() {}
    static boolean populated(Cell cell) {
        return cell.getCellType()!=CellType.BLANK&&(cell.getCellType()!=CellType.STRING||!cell.getStringCellValue().codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.CONTROL));
    }
    /** Count input fields, not the position of a sparse far-right cell. */
    static List<Integer> populatedColumns(org.apache.poi.ss.usermodel.Row row) {
        List<Integer> columns=populatedColumnsUnchecked(row);
        if(columns.size()>100)throw tooMany(row,columns.get(100));
        return columns;
    }
    /** A blog adapter verifies duplicate columns before applying its field-count limit. */
    static List<Integer> populatedColumnsUnchecked(org.apache.poi.ss.usermodel.Row row) {
        List<Integer> columns=new ArrayList<>();
        for(Cell cell:row) {
            if(!populated(cell))continue;
            columns.add(cell.getColumnIndex());
        }
        return columns;
    }
    private static BadRequestException tooMany(org.apache.poi.ss.usermodel.Row row,int column) {
        return new BadRequestException("Sheet '"+row.getSheet().getSheetName()+"' row "+(row.getRowNum()+1)+" exceeds 100 populated input columns (column "+org.apache.poi.ss.util.CellReference.convertNumToColString(column)+"). Remove unintended fields or correct the source layout before re-reading. Blank gaps and formatting alone are ignored.");
    }
    /** Preserve populated unnamed cells within the header extent, and reject data beyond it. */
    static List<Integer> inputColumns(org.apache.poi.ss.usermodel.Row head) {
        SortedSet<Integer> columns=new TreeSet<>(populatedColumns(head));
        int last=columns.isEmpty()?-1:columns.last();
        for(org.apache.poi.ss.usermodel.Row row:head.getSheet()) {
            if(row.getRowNum()<=head.getRowNum())continue;
            for(int column:populatedColumns(row)) {
                if(column>last)throw new BadRequestException("Data without a column header in "+head.getSheet().getSheetName()+" row "+(row.getRowNum()+1)+" (column "+org.apache.poi.ss.util.CellReference.convertNumToColString(column)+")");
                columns.add(column);
                if(columns.size()>100)throw tooMany(row,column);
            }
        }
        return List.copyOf(columns);
    }
    static String value(Cell cell,DataFormatter formatter) {
        if(cell!=null&&(cell.getCellType()==CellType.NUMERIC||(cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.NUMERIC))&&!DateUtil.isCellDateFormatted(cell))
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        return formatter.formatCellValue(cell);
    }
}
