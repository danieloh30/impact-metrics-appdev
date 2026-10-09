# RUN Quarkus agents — collection and data rules

## Execution

Use the Quarkus LangChain4j agent workflow defined in RUN.md. WorkbookPreparationAgent reads the original workbook with Apache POI; InstructionAgent validates frozen instructions; DigitalMetricsAgent collects current digital metrics; AttendanceAgent calculates in-person hours; AttributionAgent conserves multi-author × multi-tool allocations; Trailing90Agent refreshes historical totals; ExportAgent and ValidationAgent produce and check outputs. These are Java services and agents, not commands for IBM Bob or Python.

## Workbook data

Excel cell boundaries define fields. Text commas remain literal content. Numeric/formula-numeric cells use stored/cached values, independent of display grouping, currency, percentages, and workbook locale. Never evaluate formulas, macros, or external workbook links. Named source columns map to the canonical activity fields. Preserve source sheet and row IDs through structured processing; CSV serialization is for download/export compatibility.

Use Current month inputs plus same-month Markus/Elder blogs for current data. Use Previous months inputs for the three-calendar-month history window. Elder Month is month-level precision, not an exact publication day. Exclude undated blog rows with a warning. Preserve recorded Current total as the historical baseline and require evidence for corrections. No invented measurements or baselines.

## Supported provider metrics

| Resource | Measurement |
|---|---|
| youtube.com / youtu.be | Video view count |
| linkedin.com/posts/, /feed/update/, /pulse/ | Total reactions; not comments or reposts |
| linkedin.com/events/ | Video Views from the post analytics Video performance section |
| github.com | Repository stars from the public GitHub API |
| registry.terraform.io | Provider downloads from the Registry API |
| Other / unavailable resource | Verified manual measurement or acknowledged follow-up |

For LinkedIn Live, open the event page, choose Show in-depth analytics, follow the anchor whose href contains post-analytics, and collect Video Views. Do not use Lifetime unique viewers or Peak live viewers. Historical measurements must be refreshed for each reporting run; previous example values are not reusable evidence. The local LinkedIn/SharePoint integrations preserve user session state outside Git and never send cookies to model agents.

## Rows without URLs

For current-month digital rows, a numeric nonnegative Views / attendance plus numeric Duration (in hours) is sufficient. Preserve the supplied views and record that the inputs supplied the metric; no manual collection issue is created. Digital metrics are not multiplied by duration. If either value is missing, retain No URL — manual collection required. If supplied values are invalid, require correction before approval. In-person rows use provided attendance and duration without requiring a URL; blank duration defaults to one hour under RUN.md. Booth lead and unknown/other activity handling remain separate follow-up rules.

The exception is current-month data only. Historical rows still need a fresh lifetime total and a verified previous baseline to calculate a trailing delta. Do not substitute the historical Views / attendance or duration for a fresh total. A user may instead explicitly keep or edit historical no-URL input values in the SPA. Record that decision separately from measurements, preserve immutable originals, and label the exported values as retained/reviewed input rather than a trailing delta. Since this path calculates no delta, it does not require a historical baseline or fabricate a lifetime total. The user still approves phase 4.

## Approvals and data safety

Only runtimes is processed. Keep immutable original.xlsx, structured source rows, frozen instruction copies, JSON phase state, and immutable versioned JSON result datasets under each run. No CSV files are created during workbook preparation or any phase. Previews read structured results directly; approval digests hash their JSON bytes. Generate UTF-8 input snapshots, result CSVs, or ZIPs in memory only for explicit downloads and never persist them. Preserve separately uploaded legacy CSV inputs; read older saved CSV results only for compatibility. The SPA owns phase approval, manual corrections, downloads, and confirmed history reset. Reset preserves instruction files and local sessions. Runtime data, workbooks, outputs, and credentials remain excluded from commits.

CSV compatibility: current uploads and all generated exports use UTF-8. Separately uploaded legacy trailing_90-day.csv may use Windows-1252. Cell separators and text values inside a workbook are independent of CSV delimiter/encoding rules.

## Measurement review input context

Load original cell values for URL rows as well as no-URL rows in phases 1, 2, and 4. Display original views/attendance, duration, and baseline separately from the editable measurement. Prefill the matching field or latest manual value; do not prefill a duration/baseline correction from the wrong column. Zero is a valid prefill. Notes are optional and bounded to 1,000 characters. Missing notes are recorded explicitly as not provided, not as verified source evidence. A user still confirms/saves the selected measurement; display of a prior period's count does not itself refresh a trailing lifetime total.
