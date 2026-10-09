# RUN Quarkus agents — collection and data rules

## Execution

Use the Quarkus LangChain4j agent workflow defined in RUN.md. WorkbookPreparationAgent reads the original workbook with Apache POI; InstructionAgent validates frozen instructions; DigitalMetricsAgent collects current digital metrics; AttendanceAgent calculates in-person hours; AttributionAgent conserves multi-author × multi-tool allocations; Trailing90Agent refreshes historical totals; ExportAgent and ValidationAgent produce and check outputs. These are Java services and agents, not commands for IBM Bob or Python.

## Workbook data

Excel cell boundaries define fields. Text commas remain literal content. Numeric/formula-numeric cells use stored/cached values, independent of display grouping, currency, percentages, and workbook locale. Never evaluate formulas, macros, or external workbook links. Named source columns map to the canonical activity fields. Preserve source sheet and row IDs through structured processing; CSV serialization is for download/export compatibility.

Use Current month inputs plus same-month Markus/Elder blogs for current data. Use monthly input sheets from the separate AppDev Advocacy impact metrics workbook for the three-calendar-month history window, using one latest monthly snapshot at or before the reporting month, then filtering row dates to the displayed window. Do not merge overlapping monthly sheets. Never use AvocadoBot Previous months inputs for history. Elder Month is month-level precision, not an exact publication day. Exclude undated blog rows with a warning. Preserve recorded Current total as the historical baseline and require evidence for corrections. No invented measurements or baselines.

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

Only runtimes is processed. Keep immutable original.xlsx (AvocadoBot) and trailing-original.xlsx (metrics workbook), structured source rows, frozen instruction copies, JSON phase state, and immutable versioned JSON result datasets under each run. No CSV files are created during workbook preparation or any phase. Previews read structured results directly; approval digests hash their JSON bytes. Generate UTF-8 input snapshots, result CSVs, or ZIPs in memory only for explicit downloads and never persist them. Read older saved CSV inputs/results only for compatibility; new runs use two SharePoint workbook snapshots. The SPA owns phase approval, manual corrections, downloads, and confirmed history reset. Reset preserves instruction files and local sessions. Runtime data, workbooks, outputs, and credentials remain excluded from commits.

CSV compatibility: all generated exports use UTF-8. Older saved trailing_90-day.csv may use Windows-1252. Cell separators and text values inside a workbook are independent of CSV delimiter/encoding rules.

## Measurement review input context

Load original cell values for URL rows as well as no-URL rows in phases 1, 2, and 4. Display original views/attendance, duration, and baseline separately from the editable measurement. Prefill the matching field or latest manual value; do not prefill a duration/baseline correction from the wrong column. Zero is a valid prefill. Notes are optional and bounded to 1,000 characters. Missing notes are recorded explicitly as not provided, not as verified source evidence. A user still confirms/saves the selected measurement; display of a prior period's count does not itself refresh a trailing lifetime total.

## Configurable blog and digital collection exclusions

Use metrics.collection.manual-check-exclusions from application.properties for these sources: eldermoraes.com; www.the-main-thread.com; ibm.admin.webcasts.com; www.sigs.de; www.heise.de; www.infoq.com; quarkus.io/blog; https://www.youtube.com/watch?v=OSsatFo8Pmc; www.kevindubois.com; thedevconf.com; ec.yourlearning.ibm.com. Hosts match exactly, ignoring case and an optional www prefix; other subdomains are not included. quarkus.io/blog covers /blog and its descendants, not the rest of quarkus.io. The YouTube rule covers only watch URLs whose v is OSsatFo8Pmc, allowing unrelated query parameters; other videos retain normal collection rules.

For matching digital rows, use supplied workbook Views / attendance and skip URL collection/manual-collection issues. Zero is valid; missing, invalid, or negative values require a data correction. Duration is preserved but is not required for digital views. Historical rows retain supplied views and prior totals with Workbook content input — not a trailing delta provenance; do not fabricate a fresh lifetime total or delta. Explicit verified measurement overrides remain supported. Rows remain included in results and author/tool attribution; exclusion affects collection only.

## Historical eligibility and review context

Within the selected monthly snapshot and date window, Trailing 90-day = No (or other non-Yes flag) rows are excluded from preparation and Phase 4 refresh, exports, and review issues. Yes/true/1 includes a row; a blank flag includes digital types only. Legacy already-selected inputs lacking the flag column retain compatibility. Do not re-collect one-off in-person rows marked No, or use AvocadoBot data to overwrite a historical attributed metrics row. Historical decimal views below one can be valid conserved author/tool shares; do not round up or invent missing durations.

Preserve the actual Current total cell value, including blanks or invalid text. Never write a review message into a numeric baseline cell. Missing baselines block only a delta calculation: configured content exclusions retaining supplied inputs do not require one. Existing verified fresh measurements for non-excluded sources still require a valid baseline. Excluded content always bypasses lifetime/delta collection and baseline review, even with cached measurements. Preserve workbook views; explicit manual overrides may supply content views allocated across the URL group. Ignore old automatic measurements for excluded sources. Review popups identify source workbook, sheet, row, author, and tool; Enter baseline always edits the baseline even when URL is blank. Numeric editors start blank for invalid values. Existing saved runs apply the eligibility and collection policies on a phase retry without altering immutable source or approved earlier results.
