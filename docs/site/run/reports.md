# Use pivots and charts

The monthly report combines eligible current contributions and fresh trailing growth. It applies the selected metric model and counts an overlapping activity attribution once.

## Inspect before sharing

![Completed monthly pivots and charts with demo data](../assets/screenshots/completed-report.png)
<p class="ops-caption">Fictional report. Values demonstrate the UI; they are not published team metrics.</p>

Use the channel and tool tables to inspect impact. Contributor filters show the exact activities and source datasets behind a tool total. Published comparisons show differences; the app keeps recalculated values rather than forcing them to match an older published table.

Charts show individual months from September 2025 to the selected month:

1. **Impact hours by channel** — activity types.
2. **Impact hours by tool** — attributed tools.
3. An optional **IBM Bob** view by activity type.

The selected month uses the current calculation. Earlier months use the saved published history.

## Download the pivot workbook

Use **Download pivots & charts** in the completed-run bar, or the report's XLSX download action. `monthly-report-combined.xlsx` is the preferred artifact for editable tables, pivots and Excel charts.

To share in Word, copy the chart from Excel and choose your desired paste behavior: a linked/embedded Excel chart for editing, or an image for a fixed presentation. A UI screenshot is useful for showing the application itself, but it is not an editable chart.

## CSV outputs

| Download | Contains |
|---|---|
| `digital.csv` | Current digital contributions and collection evidence |
| `in-person.csv` | Current in-person calculations and attribution |
| `follow-up.csv` | Unresolved or other follow-up activities |
| `current.csv` | Combined current-month output |
| `updated_90-day.csv` | Historical totals, baselines, deltas and provenance |

CSV files are created in memory when downloaded. Structured source and result datasets are the working state; the pipeline does not generate intermediate CSV files on disk.

## Provisional reporting

Reporting attention identifies excluded values that need a correction. A confirmable row shows the original resource URL, workbook/sheet/row, baseline, allocated total and collection timestamp. Saving a valid correction recalculates tables and charts.

A checkbox cannot resolve a negative delta. If an attributed cumulative total is below its previous baseline, verify the allocation and correct the measurement or source baseline. Never increase a count solely to pass validation.

## Start another run

Use **Start another run** to re-read the sources, even for the same reporting month. Previous results remain saved. This is different from **Reset run history**, which deletes saved run data.
