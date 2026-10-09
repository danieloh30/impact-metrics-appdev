# RUN metrics — Quarkus workbook and agent workflow

## Source and preparation

Read two authenticated SharePoint .xlsx snapshots: AvocadoBot 2.0 - Runtimes DA impact inputs for current activities, and AppDev Advocacy impact metrics 2026 for historical metrics. Choose a reporting month in the SPA. WorkbookPreparationAgent uses Apache POI to read cells, cached formulas, and saved dates without evaluating formulas/macros/external links. Excel cells define columns: commas inside text are content, not column separators. Numeric cells use their stored numeric value rather than a formatted display such as 1,000, 25%, or a currency string.

Current month inputs is the primary activity source. Append reporting-month activities from Markus blog data (Date) and Elder blog data (Month). Blank blog Date/Month rows are excluded with counts for review. Only the separate AppDev impact metrics workbook supplies history from its month-named input sheets (for example August inputs, September inputs, October inputs) within the inclusive three-calendar-month window ending on the reporting month's last day: September 2026 means July 1–September 30. Never use AvocadoBot Previous months inputs for history. Monthly sheets already contain rolling historical rows. Select one latest monthly input sheet at or before the UI reporting month within the three-month window, then filter row dates inclusively. Never combine overlapping monthly snapshots. Show missing reporting sheets and absent month data instead of inventing them; reject a matching invalid or ambiguous sheet. Preserve historical Current total baselines; missing/invalid baselines require correction. Do not infer them from new view counts.

Agents consume structured workbook rows directly. Store source rows, phase state, and immutable versioned results as JSON datasets. Do not generate, store, or re-read CSV files during workbook preparation, phase processing, previews, or approvals. Generate AvocadoBot 2.csv and trailing_90-day.csv snapshots only in memory when explicitly downloaded for audit or compatibility. The SPA and public API accept SharePoint snapshots only; CSV/local workbook imports and runtimes seed imports are removed. Keep raw data, run state, phase data, frozen instructions, and structured results inside runtimes. Generate CSV/ZIP exports in memory only on explicit download; never persist them. No IBM Bob, Python script, external skill invocation, or user CSV export is needed to run this workflow.

## Phase 1: digital content metrics calculation

DigitalMetricsAgent processes these activity types: 3rd party conference video; Blog post / slide shares / .io updates; Digital conference / event; Livestream; Podcast or webinar (internal / external); Recorded video; Demos (archive / GitHub); Development - tools, add-ons (Registry); Development - tools (GitHub); Forum answer (IBM or practitioner community); Twitter / Facebook / LinkedIn / BlueSky / Weibo.

With a URL, use the supported provider collectors: YouTube views, LinkedIn post reactions, GitHub stars, and Terraform Registry downloads. LinkedIn Live events require Video Views from post analytics, never lifetime unique viewers. Unsupported, inaccessible, or unavailable resource measurements go exclusively to follow-up.csv for verified entry, retry, or acknowledgment.

Without a URL, supplied numeric Views / attendance AND Duration (in hours) satisfy current-month collection. Preserve the supplied view count and record input evidence; do not create a No URL manual-collection issue. Zero views is valid. Invalid supplied numbers require correction. A missing field still requires collection. Digital views remain views; multiplying duration into attendance hours applies to in-person activities only.

Store digital and follow-up result datasets with source columns and provenance. Their download filenames are digital.csv and follow-up.csv. Pause for user approval in the SPA.

## Phase 2: in-person event calculation

AttendanceAgent processes Booth duty; Conference keynote or breakout (proprietary or 3rd party); Conference workshop (proprietary or 3rd party); CSP or other partner presentation (digital or in-person); Customer meeting; Customer workshop; Customer presentation; Internal team member enablement; Meetup (user group).

Booth duty goes to follow-up.csv pending verified Field Marketing leads. For other in-person activities, calculate Attendance hours = Views / attendance × max(Duration (in hours), 1). Blank duration defaults to one hour. A URL is not required. Attendance must be numeric and nonnegative; a supplied nonnumeric duration requires correction. Store the in-person result dataset (download filename in-person.csv) and pause for approval.

## Phase 3: splitting of entries

AttributionAgent splits explicit semicolon-separated or line-separated Author of activity and Tool values inside cells into the Cartesian product. Commas inside names, titles, or other text do not define columns or attribution boundaries. A single cell value remains one value. For digital activities, allocate Views / attendance across authors × tools. For in-person activities, allocate Attendance hours into Views / attendance. Use six-decimal conserved allocation with a remainder so totals are unchanged. Follow-up attribution is split without inventing or recalculating metrics. Pause for approval.

## Phase 4: trailing 90-day calculation

Trailing90Agent processes prepared historical rows when present. Move historical Current total to Previous total, collect a fresh total once per shared resource URL, allocate it across that URL's rows, and subtract each previous baseline into Views / attendance. Current-month no-URL input evidence must not be reused as a fresh historical lifetime total. Missing fresh totals require collection; missing/invalid/negative baselines block approval until corrected. Negative deltas remain visible with a review note. Store the updated historical result dataset (download filename updated_90-day.csv) and pause for approval. Skip this phase when no historical rows were selected.

For historical rows without URLs, the SPA shows the original Views / attendance, Duration (in hours), and recorded baseline. The user may explicitly keep valid original input values or edit attendance/duration, then approve the refreshed phase. Save that per-row review decision without rewriting source data. Such rows do not claim a fresh lifetime total or trailing delta; retain the recorded total unchanged and label Metric basis as retained/reviewed input. A baseline is not required when no delta is being calculated. Always include Original Views / attendance and Original Duration (in hours) in the historical export, including unresolved rows. Missing/invalid supplied input values must be edited to valid nonnegative numbers before this choice can resolve review. URL-based fresh total collection and baseline validation remain unchanged.

## Phase 5: other activity handling

ExportAgent adds Analyst or press briefing, External conference CFP panel, Standard participation, Whitepaper, and unknown activity types to follow-up.csv. Keep booth duties and other unresolved activities there until separately requested follow-up work.

## Phase 6: combine current month data for one export

ExportAgent combines the structured digital, in-person, and follow-up datasets into the current dataset (download filename current.csv) using the exact schema:

Date,Author,Tool,Strategic initiative,Name of activity,Event,Collection,Type of activity,Trailing 90-day,Geography,Current total,Previous total,Views / attendance,Weight,Impact hours,Link / URL to resource,Notes / description,Column1,Column2,Scrape notes,total_reactions,total_comments,total_reposts,reactions_scraped_at

## Phase 7: current month totals and validation

Copy Views / attendance to Current total. ValidationAgent checks unique attribution rows, conservation of source activities, and the exact final schema. Preview structured results in the SPA. Generate CSVs individually or as a ZIP only when downloaded, using the chosen immutable result version. Phases 5–7 finish automatically after the required approvals. Model commentary is optional; Java rules determine calculations and review conditions.

## Review and reproducibility

Manual measurement popups show the immutable original views/attendance, duration, and recorded baseline for rows with or without URLs. Prefill the editable field from its matching input metric (baseline, duration, reactions, or views/attendance), or the latest user measurement if available. Source / collection notes are optional; when absent, record that no source note was provided rather than inventing evidence. Numeric, metric-type, phase and version validation remain required. Enter corrections through the SPA; publish a new structured result version and approve its digest. Approval hashes cover the structured result bytes, independent of CSV formatting. Approvals after phases 1–4 remain mandatory. Frozen copies of this RUN.md and AGENTS.md are saved per run; changing them does not modify an existing run. Never automatically recalculate follow-up metrics or fabricate missing dates, URLs, totals, or baselines.

## Configurable blog and digital collection exclusions

Use metrics.collection.manual-check-exclusions from application.properties for these sources: eldermoraes.com; www.the-main-thread.com; ibm.admin.webcasts.com; www.sigs.de; www.heise.de; www.infoq.com; quarkus.io/blog; https://www.youtube.com/watch?v=OSsatFo8Pmc; www.kevindubois.com. Hosts match exactly, ignoring case and an optional www prefix; other subdomains are not included. quarkus.io/blog covers /blog and its descendants, not the rest of quarkus.io. The YouTube rule covers only watch URLs whose v is OSsatFo8Pmc, allowing unrelated query parameters; other videos retain normal collection rules.

For matching digital rows, use supplied workbook Views / attendance and skip URL collection/manual-collection issues. Zero is valid; missing, invalid, or negative values require a data correction. Duration is preserved but is not required for digital views. Historical rows retain supplied views and prior totals with Workbook content input — not a trailing delta provenance; do not fabricate a fresh lifetime total or delta. Explicit verified measurement overrides remain supported. Rows remain included in results and author/tool attribution; exclusion affects collection only.
