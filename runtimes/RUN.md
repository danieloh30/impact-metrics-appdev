# RUN metrics — Quarkus workbook and agent workflow

## Source and preparation

Upload the original .xlsx workbook or read an authenticated SharePoint snapshot. Choose a reporting month in the SPA. WorkbookPreparationAgent uses Apache POI to read cells, cached formulas, and saved dates without evaluating formulas/macros/external links. Excel cells define columns: commas inside text are content, not column separators. Numeric cells use their stored numeric value rather than a formatted display such as 1,000, 25%, or a currency string.

Current month inputs is the primary activity source. Append reporting-month activities from Markus blog data (Date) and Elder blog data (Month). Blank blog Date/Month rows are excluded with counts for review. Previous months inputs supplies history within the inclusive three-calendar-month window ending on the reporting month's last day: September 2026 means July 1–September 30. Preserve historical Current total baselines; missing/invalid baselines require correction. Do not infer them from new view counts.

Agents consume structured workbook rows directly. Downloadable AvocadoBot 2.csv and trailing_90-day.csv are snapshots for audit and compatibility, not required analysis inputs. Legacy CSV uploads remain supported. Keep raw data, run state, phase data, frozen instructions, and CSV exports inside runtimes. No IBM Bob, Python script, external skill invocation, or user CSV export is needed to run this workflow.

## Phase 1: digital content metrics calculation

DigitalMetricsAgent processes these activity types: 3rd party conference video; Blog post / slide shares / .io updates; Digital conference / event; Livestream; Podcast or webinar (internal / external); Recorded video; Demos (archive / GitHub); Development - tools, add-ons (Registry); Development - tools (GitHub); Forum answer (IBM or practitioner community); Twitter / Facebook / LinkedIn / BlueSky / Weibo.

With a URL, use the supported provider collectors: YouTube views, LinkedIn post reactions, GitHub stars, and Terraform Registry downloads. LinkedIn Live events require Video Views from post analytics, never lifetime unique viewers. Unsupported, inaccessible, or unavailable resource measurements go exclusively to follow-up.csv for verified entry, retry, or acknowledgment.

Without a URL, supplied numeric Views / attendance AND Duration (in hours) satisfy current-month collection. Preserve the supplied view count and record input evidence; do not create a No URL manual-collection issue. Zero views is valid. Invalid supplied numbers require correction. A missing field still requires collection. Digital views remain views; multiplying duration into attendance hours applies to in-person activities only.

Write digital.csv and follow-up.csv with source columns and provenance. Pause for user approval in the SPA.

## Phase 2: in-person event calculation

AttendanceAgent processes Booth duty; Conference keynote or breakout (proprietary or 3rd party); Conference workshop (proprietary or 3rd party); CSP or other partner presentation (digital or in-person); Customer meeting; Customer workshop; Customer presentation; Internal team member enablement; Meetup (user group).

Booth duty goes to follow-up.csv pending verified Field Marketing leads. For other in-person activities, calculate Attendance hours = Views / attendance × max(Duration (in hours), 1). Blank duration defaults to one hour. A URL is not required. Attendance must be numeric and nonnegative; a supplied nonnumeric duration requires correction. Write in-person.csv and pause for approval.

## Phase 3: splitting of entries

AttributionAgent splits explicit semicolon-separated or line-separated Author of activity and Tool values inside cells into the Cartesian product. Commas inside names, titles, or other text do not define columns or attribution boundaries. A single cell value remains one value. For digital activities, allocate Views / attendance across authors × tools. For in-person activities, allocate Attendance hours into Views / attendance. Use six-decimal conserved allocation with a remainder so totals are unchanged. Follow-up attribution is split without inventing or recalculating metrics. Pause for approval.

## Phase 4: trailing 90-day calculation

Trailing90Agent processes prepared historical rows when present. Move historical Current total to Previous total, collect a fresh total once per shared resource URL, allocate it across that URL's rows, and subtract each previous baseline into Views / attendance. Current-month no-URL input evidence must not be reused as a fresh historical lifetime total. Missing fresh totals require collection; missing/invalid/negative baselines block approval until corrected. Negative deltas remain visible with a review note. Write updated_90-day.csv and pause for approval. Skip this phase when no historical rows were selected.

For historical rows without URLs, the SPA shows the original Views / attendance, Duration (in hours), and recorded baseline. The user may explicitly keep valid original input values or edit attendance/duration, then approve the refreshed phase. Save that per-row review decision without rewriting source data. Such rows do not claim a fresh lifetime total or trailing delta; retain the recorded total unchanged and label Metric basis as retained/reviewed input. A baseline is not required when no delta is being calculated. Always include Original Views / attendance and Original Duration (in hours) in the historical export, including unresolved rows. Missing/invalid supplied input values must be edited to valid nonnegative numbers before this choice can resolve review. URL-based fresh total collection and baseline validation remain unchanged.

## Phase 5: other activity handling

ExportAgent adds Analyst or press briefing, External conference CFP panel, Standard participation, Whitepaper, and unknown activity types to follow-up.csv. Keep booth duties and other unresolved activities there until separately requested follow-up work.

## Phase 6: combine current month data for one export

ExportAgent combines digital.csv, in-person.csv, and follow-up.csv into current.csv using the exact schema:

Date,Author,Tool,Strategic initiative,Name of activity,Event,Collection,Type of activity,Trailing 90-day,Geography,Current total,Previous total,Views / attendance,Weight,Impact hours,Link / URL to resource,Notes / description,Column1,Column2,Scrape notes,total_reactions,total_comments,total_reposts,reactions_scraped_at

## Phase 7: current month totals and validation

Copy Views / attendance to Current total. ValidationAgent checks unique attribution rows, conservation of source activities, and the exact final schema. Download CSVs individually or as a ZIP in the SPA. Phases 5–7 finish automatically after the required approvals. Model commentary is optional; Java rules determine calculations and review conditions.

## Review and reproducibility

Record source/evidence for manual values. Enter corrections through the SPA; regenerate artifacts and approve their new version. Approvals after phases 1–4 remain mandatory. Frozen copies of this RUN.md and AGENTS.md are saved per run; changing them does not modify an existing run. Never automatically recalculate follow-up metrics or fabricate missing dates, URLs, totals, or baselines.
