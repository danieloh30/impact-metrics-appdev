package com.ibm.advocacy.service;

import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.*;
import java.time.LocalDate;
import java.util.*;

/** Stable date sorting of complete activity rows within their existing slots. */
final class MonthlyRowSort {
    private record Dated(XSSFRow row,LocalDate date) {}
    private record Saved(XSSFRow row,Map<Integer,XSSFHyperlink> links,Map<Integer,Comment> comments) {}
    private static final class SnapshotRow extends XSSFRow {
        SnapshotRow(CTRow row,XSSFSheet sheet){super(row,sheet);}
    }
    private MonthlyRowSort() {}
    static void sort(XSSFSheet sheet,int header,Map<String,Integer> columns,DataFormatter format) {
        List<Dated> original=new ArrayList<>();
        Map<String,Integer> identity=Map.of("Date",columns.get("Date"),"Name of activity",columns.get("Name of activity"));
        for(int i=header+1;i<=sheet.getLastRowNum();i++) {
            XSSFRow row=sheet.getRow(i);if(row==null)continue;
            var data=MonthlyInputWorkbook.read(row,identity,format);
            if(!data.get("Date").isBlank()&&!data.get("Name of activity").isBlank())original.add(new Dated(row,LocalDate.parse(data.get("Date"))));
        }
        List<Dated> ordered=new ArrayList<>(original);ordered.sort(Comparator.comparing(Dated::date));
        Map<Integer,Integer> moves=new LinkedHashMap<>();
        for(int i=0;i<original.size();i++){int from=ordered.get(i).row().getRowNum(),to=original.get(i).row().getRowNum();if(from!=to)moves.put(from,to);}
        if(moves.isEmpty())return;
        List<CellRangeAddress> merged=sheet.getMergedRegions();
        for(CellRangeAddress region:merged)if(region.getFirstRow()!=region.getLastRow()&&moves.keySet().stream().anyMatch(r->r>=region.getFirstRow()&&r<=region.getLastRow()))
            throw new BadRequestException("Cannot date-sort activity rows across merged range "+region.formatAsString()+" in "+sheet.getSheetName()+". Unmerge that range and preview again; no changes were published.");
        Map<Integer,Saved> saved=new LinkedHashMap<>();int spare=sheet.getLastRowNum()+1;
        for(Dated item:original) {
            XSSFRow row=item.row();if(!moves.containsKey(row.getRowNum()))continue;
            CTRow copy=(CTRow)row.getCTRow().copy();Map<Integer,XSSFHyperlink> links=new LinkedHashMap<>();Map<Integer,Comment> comments=new LinkedHashMap<>();
            for(Cell cell:row) {
                if(cell.isPartOfArrayFormulaGroup())throw new BadRequestException("Cannot date-sort array formula at "+cell.getAddress()+" in "+sheet.getSheetName()+". Convert it to individual row formulas and preview again; no changes were published.");
                if(cell.getCellType()==CellType.FORMULA)for(CTCell cloned:copy.getCArray())if(new CellReference(cloned.getR()).getCol()==cell.getColumnIndex()){CTCellFormula formula=CTCellFormula.Factory.newInstance();formula.setStringValue(cell.getCellFormula());cloned.setF(formula);}
                if(cell.getHyperlink()!=null)links.put(cell.getColumnIndex(),new XSSFHyperlink(cell.getHyperlink()));
                if(cell.getCellComment()!=null)comments.put(cell.getColumnIndex(),cell.getCellComment());
            }
            saved.put(row.getRowNum(),new Saved(new SnapshotRow(copy,sheet),links,comments));
        }
        // Park comments away from replaced cells so POI keeps their text, authors and shapes.
        for(var entry:saved.entrySet()) {
            XSSFRow source=sheet.getRow(entry.getKey());
            for(var comment:entry.getValue().comments().entrySet())comment.getValue().setAddress(new CellAddress(spare,comment.getKey()));
            spare++;
            for(int column:entry.getValue().links().keySet())source.getCell(column).removeHyperlink();
        }
        CellCopyPolicy policy=new CellCopyPolicy.Builder().cellValue(true).cellStyle(true).cellFormula(true).copyHyperlink(false).mergedRegions(false).rowHeight(true).build();
        for(var move:moves.entrySet()) {
            Saved source=saved.get(move.getKey());XSSFRow target=sheet.createRow(move.getValue());
            CTRow attributes=(CTRow)source.row().getCTRow().copy();attributes.setCArray(new CTCell[0]);attributes.setR(move.getValue()+1L);target.getCTRow().set(attributes);
            target.copyRowFrom(source.row(),policy);
            source.links().forEach((column,link)->target.getCell(column).setHyperlink(link));
        }
        for(var move:moves.entrySet())for(var comment:saved.get(move.getKey()).comments().entrySet()) {
            XSSFCell cell=sheet.getRow(move.getValue()).getCell(comment.getKey());comment.getValue().setAddress(cell.getAddress());cell.setCellComment(comment.getValue());
        }
        // Horizontal cell groups follow the same activity; header/footer groups remain fixed.
        for(int i=0;i<merged.size();i++){CellRangeAddress region=merged.get(i);Integer target=moves.get(region.getFirstRow());if(region.getFirstRow()==region.getLastRow()&&target!=null){CellRangeAddress moved=region.copy();moved.setFirstRow(target);moved.setLastRow(target);sheet.getCTWorksheet().getMergeCells().getMergeCellArray(i).setRef(moved.formatAsString());}}
        sheet.setForceFormulaRecalculation(true);
    }
}
