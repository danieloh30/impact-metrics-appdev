package com.ibm.advocacy.service;

import org.apache.poi.ss.usermodel.*;
import java.math.BigDecimal;

/** Numeric Excel cells use their stored value, never locale/display formatting. */
final class WorkbookCells {
    private WorkbookCells() {}
    static String value(Cell cell,DataFormatter formatter) {
        if(cell!=null&&(cell.getCellType()==CellType.NUMERIC||(cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.NUMERIC))&&!DateUtil.isCellDateFormatted(cell))
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        return formatter.formatCellValue(cell);
    }
}
