# Impact Metrics AppDev

Quarkus LangChain4j application and React SPA for all seven RUN metrics phases. Upload the original multi-sheet `.xlsx` workbook to prepare structured activity inputs, or import existing `AvocadoBot 2.csv` and optional `trailing_90-day.csv`, review phase approvals, enter verified measurements, preview results, and download CSVs or a ZIP. Workbook cells flow directly into specialized Quarkus agents and Java decimal calculations; CSV files are generated in memory only when explicitly downloaded; no intermediate CSV files are created. No IBM Bob or Python execution is required.

## Start locally

Requires Java 21+, Node 20.19+ or 22.12+, and npm. The Maven wrapper is included. On this Mac, `dev.sh` finds the installed Homebrew JDK when system Java is not configured.

```bash
cd /Users/danieloh/developer-advocacy/tools/impact-metrics-appdev
./dev.sh
```

Open **http://127.0.0.1:8097**. Quinoa builds and serves the SPA through Quarkus; no separate frontend server is needed. First startup requires dependency downloads. Override the port with `./dev.sh -Dquarkus.http.port=8098` if necessary.

The **Import from SharePoint** tab is first and opens by default with the workbook link prefilled. Source buttons use a compact selector; connection controls sit together, with the workbook-read action aligned separately and stacked on mobile. The page checks saved connection readiness without starting sign-in. Connect if needed, then choose **Read SharePoint workbook**. To upload a local file instead, select **Upload Excel file**, choose the `.xlsx` file, and choose **Read workbook**. Select the reporting month and source sheets, then choose **Prepare workbook inputs**. Alternatively, expand **Import existing CSV files instead** or choose **Load runtimes inputs**. Then accept the displayed calculation contract, and start processing. Approve phases 1–3 and optional phase 4; phases 5–7 then finish automatically. Invalid numeric values require correction. Unresolved collection issues require explicit acknowledgment. Download `digital.csv`, `in-person.csv`, `follow-up.csv`, optional `updated_90-day.csv`, and `current.csv` individually or as ZIP.

Restarted/interrupted runs become retryable and resume from immutable phase inputs. Successful measurements are cached per run. Approvals reference exact artifact versions; edits generate fresh versions.

## Workbook-first agent processing

The app's `runtimes/RUN.md` and `runtimes/AGENTS.md` now describe the Quarkus workbook workflow rather than Python scripts or Bob commands. Existing runs keep their frozen instructions; create a new run to use the updated rules.

WorkbookPreparationAgent reads Excel cells directly into structured row data. That data feeds all phase agents without serializing and reparsing CSV. A comma in a name/title is ordinary text; Excel cell boundaries define columns. Semicolons and line breaks inside Author/Tool cells explicitly separate multiple values for attribution. A numeric cell uses its stored value, including the saved result of a formula, rather than a formatted `1,000`, currency, or percentage display. Dates retain their source date/month handling. Input CSV snapshots are generated only on demand from immutable source data, and legacy CSV imports remain compatible.

For a **current-month digital activity without a URL**, valid nonnegative `Views / attendance` and numeric `Duration (in hours)` let it proceed into `digital.csv` without a manual collection warning. Supplied view counts remain views; only in-person rows multiply attendance by the minimum-one-hour duration. Zero views is valid. Missing values still require collection; invalid supplied values require correction. This rule does not bypass a URL's provider collection or replace the fresh lifetime totals needed for trailing deltas.

## Excel workbook import

The legacy Python/Bob RUN.md assumed that `trailing_90-day.csv` already exists; it does not identify its creator or provide a creation phase. The application provides `WorkbookPreparationAgent`, a deterministic LangChain4j agent backed by the [Quarkus Apache POI extension](https://docs.quarkiverse.io/quarkus-poi/dev/index.html), to prepare the inputs from the original workbook.

1. Upload an unencrypted `.xlsx` workbook (maximum 6 MB) and choose **Read workbook**.
2. Review detected sheets, select the reporting month, and choose the authoritative current-month and historical activity sheets. The reporting month defaults to the current month; the month picker also accepts earlier months such as September 2026. When available, **Current month inputs** is the suggested current sheet and **Previous months inputs** is preselected for history. A compatible **Bob csv** sheet remains selectable but is not selected automatically as history when the authoritative historical input sheet is available. For other workbook layouts, the newest eligible activity sheet is suggested and other eligible sheets are offered as history. Avoid selecting duplicate copies of the same activities.
3. Choose **Prepare workbook inputs**, review the calculation contract, then start the existing phase agents. Original RUN approval gates are preserved.

| Input | Selection and preparation |
|---|---|
| `AvocadoBot 2.csv` | Activities dated within the selected reporting month from the selected current sheet, plus matching rows from visible **Markus blog data** and **Elder blog data** sheets |
| `trailing_90-day.csv` | All activities dated within the inclusive three-calendar-month window ending on the reporting month's last day, from the selected history sheets. Selection uses Date of activity (or Date), without filtering activity types or Trailing 90-day flags. |
| Previous baseline | Preserve the historical `Current total`; never substitute raw Views / attendance or zero. Multi-author × multi-tool submissions split their recorded baseline with conserved decimal allocation. Missing, invalid, or negative baselines block phase-4 approval until corrected with evidence. |

Examples: September 2026 selects July 1–September 30; October 2026 selects August 1–October 31. These are three calendar months, rather than exactly 90 elapsed days. Current-month input is selected separately from the current sheet. If the same sheet supplies both inputs, its reporting-month activities appear in both; the UI displays a warning. Choose authoritative separate current and historical sheets to avoid duplicate processing.

The workbook requires `Date of activity` (or `Date`), `Author of activity` (or `Author`), `Type of activity`, and `Link / URL to resource` headers in the first 30 rows of each selected sheet. Header matching ignores case, repeated whitespace, line breaks, and nonbreaking spaces. The raw workbook header **Link / URL to public resource** maps to **Link / URL to resource**; scoped headers such as **Author of activity (Java Runtime)** and **Tool (Java Runtime)** map to **Author of activity** and **Tool**. Content/deck and trip-report links stay separate and are not substituted for a missing public URL. Extra named columns are retained. Excel dates, ISO dates, and US M/D/YYYY dates are supported. Formula cells use saved cached values; recalculate and save in Excel first. The application never evaluates formulas, macros, or external links. Hidden sheets are excluded. Invalid dates, duplicate headers after normalization, Excel errors, and data without headers are rejected with a readable message. Limits: 100 sheets, 20,000 rows per sheet, and 100 columns.

The two blog sheets supplement the current input automatically for both uploads and SharePoint snapshots. **Markus blog data** is filtered by `Date`; **Elder blog data** is filtered by `Month`. The UI shows each sheet's matching count when you change the reporting month. Their source-specific columns map as follows:

| Blog source column | Generated activity column |
|---|---|
| `Date` / `Month` | `Date of activity` |
| `Name` | `Author of activity` |
| `Title` | `Name of activity` |
| `Link` / `blog` | `Link / URL to resource` |
| `Views` / `views` | `Views / attendance` |
| `Type` | `Type of activity`; blank values default to `Blog post / slide shares / .io updates` |
| `Current`, `Previous`, `Trailing` | `Current total`, `Previous total`, `Trailing 90-day` |

Tool, author, title, URL, saved view counts, and other source values are preserved. Supported month values include Excel dates, ISO/US dates, `2026-09`, `Sep 2026`, and `September 2026`. A month-only value uses its first day for grouping and records `Source date precision = Month only`; it does not claim an exact publication day. Every appended row records `Source sheet` and `Source row`. Rows with blank Date/Month are excluded and counted in a visible warning; fill their month in Excel and upload again to include them. Invalid nonblank dates or malformed visible blog sheets produce a validation error. Hidden blog sheets remain excluded. No dates, URLs, authors, or view counts are invented. Blog supplements are not offered as primary/history sheets, preventing accidental double import; they append to the current input only. Their per-sheet appended and undated counts are saved in run state as `blogRows` and `blogSkippedRows`, and shown under **Prepared workbook inputs**. A month can be prepared from matching blog rows even when the selected primary sheet has no rows for that month. Historical input selection and baseline review remain unchanged.

If historical sheets contain only raw submissions without processed `Current total` values, the app can generate the trailing input, but those activities require verified baseline entry during phase 4. Fresh scraped totals cannot reconstruct historical totals. No trailing dataset is prepared when no activities qualify; no input CSV is created unless downloaded.

Both optional input CSV snapshots use UTF-8 and are generated in memory when downloaded from **Prepared workbook inputs**; preparation does not create CSV files. The original workbook is preserved as `rawdata/original.xlsx` under its run; filenames, sheet choices, reporting month, date window, SHA-256, and missing-baseline count are recorded in run state. All workbook data remains inside ignored `runtimes/runs/` storage.

Inspection includes `suggestedCurrentSheet` and `suggestedHistorySheets`; the SPA uses these recommendations for both file upload and SharePoint imports. Excluded sheets report the missing activity-header structure or hidden status. A sheet row count describes all dated rows before the reporting-month/window filters.

Endpoints: `POST /api/workbooks/inspect` (multipart `workbook`), `POST /api/runs/from-workbook` (multipart `workbook`, `month`, `currentSheet`, and JSON array `historySheets`), and `GET /api/runs/{id}/inputs/{name}/download`. Input downloads allow only `AvocadoBot 2.csv` and `trailing_90-day.csv`; final exports retain their existing endpoints.

## Direct SharePoint workbook import

Choose **Import from SharePoint**. The link field is prefilled with the AvocadoBot 2.0 Runtimes workbook sharing link and remains editable for another workbook. Review or replace the link, then choose **Connect SharePoint**. A separate local Chrome window opens with an app-specific profile, rather than a tab in your usual browser; complete IBM sign-in and MFA there. The app waits for the site-specific `FedAuth` cookie (a cross-site `rtFa` cookie alone does not confirm sign-in), then saves the authenticated state and closes the sign-in window automatically. “Read SharePoint workbook” stays disabled until sign-in and browser cleanup finish. The app never asks for your password. When the connection status confirms sign-in, choose **Read SharePoint workbook**. A progress panel shows the actual connection, download, file validation, and sheet-reading stages with elapsed time. Its animated bar is indeterminate because SharePoint does not provide byte progress through this integration; it does not show an estimated percentage. The panel clears on success or error and supports small screens, screen readers, and reduced motion. Select the month and sheets, then prepare the inputs through the same workbook agent flow.

This local workflow reuses the existing Playwright browser integration and needs no Entra application registration. It supports HTTPS `.xlsx` links on `ibm-my.sharepoint.com` using `/:x:/r/` or direct personal/site/team file paths. Other hosts and formats are rejected. Authentication does not grant additional file permissions; your IBM account must already be allowed to download the workbook. If organizational browser or download policies prevent it, download the original `.xlsx` through your approved browser and use **Upload Excel file**.

Chrome must be installed on the computer running Quarkus. Defaults use the installed Chrome channel and a separate profile under `runtimes/.sessions/sharepoint`, preserved by history reset and excluded from Git. Session cookies and browser storage are explicitly saved to `runtimes/.sessions/sharepoint-auth.json` before the sign-in browser closes and restored for workbook downloads; relying on the Chrome profile alone can lose session cookies. After upgrading, choose **Connect SharePoint** once to save the session. If IBM expires or revokes it, reconnect. Optional settings: `METRICS_SHAREPOINT_BROWSER_CHANNEL`, `METRICS_SHAREPOINT_BROWSER_EXECUTABLE`, and `METRICS_SHAREPOINT_ENABLED=false`. Browser sign-in is local to the Quarkus host; it is not an authentication flow for a remotely hosted server.

Inspection downloads one snapshot, held in a bounded in-memory cache for 30 minutes (maximum five snapshots). Structured workbook preparation uses those exact bytes, consumes the snapshot, and saves `rawdata/original.xlsx`, its SHA-256, filename, and a canonical source URL without sharing-query tokens with the run. Restarting the app expires pending snapshots; read the workbook again. SharePoint downloads use the authenticated browser cookie jar through an HTTP request and retain the workbook snapshot in memory. If the initial request returns a sign-in, viewer, missing-file, or access response, the app opens the original sharing link to refresh/redeem access, retries the standard file endpoint, then tries the direct file link with `download=1`. It validates the HTTP response and Excel ZIP signature before inspecting sheets. HTTP 401 requires reconnecting; permission errors, HTML responses, missing files, and network timeouts have distinct messages. A timeout or HTTP 403 does not mark a valid sign-in disconnected. Models receive neither browser cookies nor full workbooks.

Endpoints: `GET/POST /api/sharepoint/session` (includes `downloadStage` for progress polling), `POST /api/workbooks/from-sharepoint` with JSON `{"url":"..."}`, and `POST /api/runs/from-sharepoint` with multipart `sourceToken`, `month`, `currentSheet`, and JSON-array `historySheets`. An expired/consumed token returns 410. Automated tests use synthetic workbook transport and a loopback HTTP server rather than IBM credentials. A live download and inspection of the original IBM workbook succeeded using the existing authenticated local session, returning eight sheets without creating a metrics run.

References: [Microsoft SharePoint authentication](https://learn.microsoft.com/en-us/sharepoint/authentication), [Playwright authentication state](https://playwright.dev/java/docs/auth), [Playwright authenticated HTTP requests](https://playwright.dev/java/docs/api/class-apirequestcontext) and [persistent browser contexts](https://playwright.dev/java/docs/api/class-browsertype#browser-type-launch-persistent-context).

## Web interface


The **AppDev Developer Advocacy** workspace uses self-hosted IBM Plex Sans, a navy sidebar, blue action controls, and responsive cards for imports, run summaries, collection review, and exports. The mobile layout keeps workspace identity and recent runs accessible. Keyboard focus indicators, a skip link, and reduced-motion support are included; fonts load locally without third-party requests.

## Source structure

Backend packages under `src/main/java/com/ibm/advocacy` separate responsibilities:

```text
agentic/   # individual phase agents, sequence workflow, and model reviewers
model/     # row/run data, measurements, approvals, artifacts, and CSV schema
resource/  # REST endpoints and API error mapping
service/   # durable run orchestration, seven-phase pipeline, decimal calculations
collector/ # public metrics providers and authenticated LinkedIn collection
csv/       # CSV parsing, encoding, and serialization
storage/   # runtimes boundary and atomic state/file persistence
```

`src/test/java/com/ibm/advocacy/service` contains calculation and workflow integration tests. The React SPA remains in `src/main/webui`, with Quarkus configuration in `src/main/resources`. Package refactoring preserves REST paths, CSV formats, and persisted run JSON; runtime data stays in `runtimes`.

## Runtime storage

All production raw data and outputs stay under `runtimes`. Seed CSVs remain in place; the app never processes the original project's `ai` or `devops` folders.

```text
runtimes/
  RUN.md
  AGENTS.md
  AvocadoBot 2.csv
  trailing_90-day.csv
  runs/<run-id>/
    rawdata/           # original.xlsx; original CSV uploads only for legacy imports
    instructions/     # frozen instruction snapshots
    state/            # atomic state, measurements, approvals, row lineage
    output/<version>/ # immutable JSON result datasets, e.g. digital.json
  .sessions/          # optional LinkedIn browser profile
  .uploads/           # temporary multipart uploads
```

Git excludes runtime CSVs, every file under `runtimes/runs/`, sessions, uploads, and future rawdata/output folders. Only `runtimes/RUN.md` and `runtimes/AGENTS.md` are versioned.

Filesystem access rejects traversal and symlink escapes. Current input and workbook-generated inputs are UTF-8; separately uploaded legacy trailing CSVs are Windows-1252; exports are UTF-8. Structured phase datasets retain source columns and row IDs. `current.csv` uses the exact 24-column RUN schema.

The contract fixes the legacy script gaps: unresolved digital rows are exclusively follow-up; attribution divides by authors × tools and conserves totals at six decimals; in-person Views / attendance contains allocated attendance hours; trailing totals preserve previous baselines, allocate shared URL totals once, and calculate deltas. Negative deltas remain visible. Phase-5 and unknown activity types are retained. Booth-lead collection and later follow-up recalculation require a separate request.

RUN.md and AGENTS.md must match the reviewed instruction SHA-256 in `metrics.instructions.sha256`. If instructions change, review and implement the new rules before updating that hash. Each run freezes its own copies.

## Agents and optional model review

`agentic.PhaseWorkflow` is a LangChain4j sequence of InstructionAgent, DigitalMetricsAgent, AttendanceAgent, AttributionAgent, Trailing90Agent, ExportAgent, and ValidationAgent, with isolated AgenticScope IDs. Durable Java orchestration enforces approvals and recovery.

Plain Java agents perform exact calculations and I/O. Optional InstructionReviewer and QualityReviewer model agents explain bounded contract/phase summaries. Models cannot invent measurements or approve phases. The complete workflow runs without model credentials; commentary is disabled by default.

To enable commentary before starting, set `METRICS_AI_ENABLED=true`, `METRICS_MODEL_API_KEY`, `METRICS_MODEL_BASE_URL`, and `METRICS_MODEL_NAME`. The configured endpoint must support the OpenAI-compatible chat API. Frozen instructions and bounded issue summaries are sent to it; full CSVs and personal columns are not sent. Model failures preserve deterministic processing.

## Collection and LinkedIn

Public collectors support YouTube viewCount, GitHub repository stars, and Terraform Registry provider downloads, with fixed hosts, private-address checks, bounded redirects, timeouts, and response-size limits. Unsupported sites and provider/rate-limit failures create manual tasks. Evidence and collection timestamps are saved.

For LinkedIn, run `npx playwright install chromium` from `src/main/webui`, then choose **Connect LinkedIn** and sign in to the dedicated browser window. Use **Check connection**, then **Retry unresolved collection** at the current review. Set `METRICS_LINKEDIN_ENABLED=false` to disable browser collection.

Posts/feed/pulse use total reactions only. Events follow the admin post-analytics link and require **Video Views** from Video performance. Lifetime unique viewers and peak concurrent viewers are not accepted substitutes. Missing login/admin access or changed markup falls back to manual entry. Authenticated LinkedIn collection and live LLM responses have not been verified in this session.

## Reset run history

Saved runs persist across dev-mode restarts in `runtimes/runs/`; this application stores history in files rather than PostgreSQL. Choose **Reset run history** in the sidebar and confirm **Delete all run history** to clear every saved run, its uploaded copies, measurements, approvals, and output versions. The SPA then clears the selected run, previews, counters, and browser's remembered run ID.

Reset keeps the seed CSVs directly under `runtimes/`, `RUN.md`, `AGENTS.md`, and `.sessions/` for LinkedIn authentication. Download any results you want to retain first. Reset is rejected while a run is actively processing; wait for completion or cancel and wait for cancellation to finish. Filesystem deletion does not follow symlinks. History is not automatically erased on startup. Connected live streams receive a final `RESET` event and close when their run is deleted; the SPA clears the stale selection in other open tabs as well.

API: `POST /api/runs/reset` with JSON `{"confirm":true}` returns `{"deletedRuns":N}`. Missing confirmation returns 400; active processing returns 409. Imports and run actions are serialized against reset.

Browser workflow tests should use a separate runtime root ending in `runtimes` to keep synthetic runs out of the working history.

## Measurement popup input values

The measurement popup loads saved row context for URL and no-URL rows in phases 1, 2, and 4, including existing saved runs. It displays original Views / attendance, Duration (in hours), and the baseline separately, and prefills the editable total using the metric being reviewed. A blog view input of `1000` starts at `1000`; a zero starts at `0`. Baseline corrections use Current total, duration corrections use Duration, and reactions use the supplied reactions when available. The latest manually saved measurement overrides the editable prefill without overwriting displayed originals. Numeric grouping in legacy values is normalized for the numeric field.

**Source / collection note (optional)** can be left blank. No note is fabricated: the run records “source / collection note not provided.” Saving still requires a valid nonnegative number, the right metric type, and the current phase/version. In trailing review, confirm or edit the prefilled value before treating it as a fresh measured total; the immutable original and baseline remain visible for comparison. URL rows keep the existing fresh-total/delta workflow and do not get the no-URL retention action.

Endpoint: `GET /api/runs/{id}/measurement-input?rowId=...&type=...` returns immutable originals and the metric-specific editable prefill. The existing measurement POST accepts an omitted/blank `evidence` note.

## Review original trailing input values

For a historical row without a URL, choose its **Enter views** (or baseline correction) action to open **Review original input**. The app reads original values from the saved historical row, including runs imported before this feature. The dialog shows original Views / attendance, Duration (in hours), and the recorded previous baseline. Its editable attendance and duration fields are prefilled from the original row or the last saved input review.

- **Keep original values** records an explicit review without changing the source values or requiring a collection note.
- **Save edited inputs & refresh review** saves nonnegative numeric attendance and duration; a review note is optional. Original source data remains unchanged.
- **Enter a fresh total instead** switches to the existing verified-measurement form. Its total starts blank so an initial attendance count cannot accidentally be submitted as a fresh lifetime total.

After keeping/editing, collection and baseline issues for that row clear; approve phase 4 to complete the run. Baselines are required for actual trailing deltas, not for this explicit decision to retain input data without computing a delta. Missing input values cannot be kept until valid numbers are entered. Saved decisions survive refresh/restart, require the current review version, and are limited to no-URL rows in phase 4.

`updated_90-day.csv` preserves `Original Views / attendance` and `Original Duration (in hours)` for all rows. Reviewed no-URL rows write the retained/edited values and duration, preserve the recorded total, and carry `Metric basis = Retained input — not a trailing delta` or `Reviewed input — not a trailing delta`. Unresolved rows keep their originals in the extra columns without inventing a delta. This makes completing review without updating values explicit and auditable.

Endpoints: `GET /api/runs/{id}/trailing-input?rowId=...` returns original/effective input values; `POST /api/runs/{id}/trailing-inputs` accepts `version`, `rowId`, `keepOriginal`, optional `views`/`duration`, and optional `note`, and regenerates the review artifacts.

## Collection & data review

A row appears in **Collection & data review** when the backend adds an entry to `run.issues`. The SPA displays the section whenever that list is nonempty. These are deterministic Java rules; model agents do not decide whether a row needs manual review.

| Condition | Review category | Approval behavior |
|---|---|---|
| Current digital row: no URL but valid supplied Views / attendance and Duration (in hours) | No collection issue | Proceeds using input metrics |
| Current digital row: no URL and missing Views / attendance or Duration (in hours); or trailing row: no URL | Manual collection | Enter a verified measurement or acknowledge follow-up |
| During URL collection: unsupported website, malformed URL, failed request, or unavailable metric | Manual collection | Enter a verified measurement, retry, or acknowledge unresolved collection |
| LinkedIn login/admin access unavailable, or reactions/Video Views cannot be extracted | Manual collection | Enter a verified measurement, retry, or acknowledge unresolved collection |
| Supplied current no-URL digital metrics are invalid or views are negative | Correction required | Must correct before approval |
| In-person attendance is blank, nonnumeric, or negative | Correction required | Must correct before approval |
| Duration is supplied but nonnumeric | Correction required | Must correct before approval |
| Trailing baseline is blank, nonnumeric, or negative for a row calculating a delta (explicitly retained/reviewed no-URL inputs are exempt) | Correction required | Must correct before approval |

Missing in-person duration defaults to one hour. Manual entries require a nonnegative numeric value and the expected metric type. Source / collection notes are optional (maximum 1,000 characters); omitted notes are recorded as not provided. Entering a measurement regenerates the phase artifacts and creates a new version for approval. Collection issues are scoped to the current phase; trailing collection reports one collection issue per shared URL group.

Currently, booth duty and other/unknown activity types go into `follow-up.csv` without appearing in this review section. Negative trailing deltas receive a CSV review note but do not create a review-panel issue.

Implementation: [review panel](src/main/webui/src/main.tsx), [phase issue rules](src/main/java/com/ibm/advocacy/service/Pipeline.java), [provider collection failures](src/main/java/com/ibm/advocacy/collector/MetricCollectors.java), [LinkedIn collection](src/main/java/com/ibm/advocacy/collector/LinkedInCollector.java), and [approval/manual-entry validation](src/main/java/com/ibm/advocacy/service/RunService.java).

## Verification

Passed **66 Java tests**, TypeScript/Vite build, and **5 browser tests** through the Quarkus-served SPA. Browser tests cover Excel upload, editable reporting month and calendar windows, generated input downloads, synthetic SharePoint sign-in/snapshot UI with real workbook processing, both CSV inputs, manual collection, four approvals, all five downloads, ZIP, exact final schema, reload recovery, mobile layout, confirmed history reset, and reset notification across open tabs. Both SharePoint readiness/expired-access and download-progress browser regressions pass. The progress test verifies server-reported stages, elapsed time, indeterminate accessibility semantics, disabled controls, and cleanup after success and HTTP 403. Two real-Chrome Java tests verify that a session-only cookie survives sign-in browser closure and two subsequent download-browser launches, and that missing state or a cross-site refresh cookie alone cannot confirm site sign-in. These browser-state tests require Chrome installed locally and use synthetic cookies without contacting IBM. Three additional real-Chrome/loopback HTTP tests verify restored-cookie downloads and redirects, distinct HTTP/HTML/size errors, and original-link redemption with refreshed cookies and a direct-download fallback. The raw-workbook defaults UI regression passes: the current input sheet and authoritative historical sheet are selected while a compatible Bob csv export is left unchecked. Three Java header tests verify scoped header mapping, public URL preservation, preferred sheet recommendations through the inspection API, and duplicate-alias rejection. Inspection of the original uploaded workbook confirms that its current and previous input sheets are eligible after normalization. Java workbook/source tests verify date boundaries, all dated history rows, baseline allocation and correction, cached formulas, Unicode, source validation, snapshot reuse/consumption, source provenance, and HTTP 400 validation through agent wrappers. Java reset tests verify seed/session preservation, required confirmation, active-worker rejection, symlink boundaries, and normal live-stream completion after deletion. Fixtures are synthetic, not live impact measurements. Live IBM SharePoint download and workbook inspection succeeded with the existing saved session (eight sheets); manual SSO/MFA was not rerun during this verification. Frontend audit reported zero vulnerabilities. Six blog workbook regressions verify both source schemas, reporting-month boundaries, cached view formulas, author attribution after CSV round-trip, month-only Excel dates, hidden/undated exclusions, malformed-date validation, historical-input isolation, and agent/API persistence with generated input downloads. Two targeted browser regressions passed for the updated workbook defaults and blog month counts, undated warnings, separate historical selection, and mobile layout.

Java tests mirror the service package in `src/test/java/com/ibm/advocacy/service`. Use Quarkus Dev UI/MCP testing, or `./mvnw test` in CI. For browser tests, start an isolated server from the project root in a separate terminal:

```bash
mkdir -p /tmp/impact-metrics-e2e/runtimes
cp runtimes/RUN.md runtimes/AGENTS.md /tmp/impact-metrics-e2e/runtimes/
./dev.sh -Dquarkus.http.port=8098 \
  -Dmetrics.runtime-root=/tmp/impact-metrics-e2e/runtimes \
  -Dmetrics.collection.enabled=false
```

Then run the browser suite in another terminal:

```bash
cd src/main/webui
npm ci
npx playwright install chromium
METRICS_URL=http://127.0.0.1:8098 METRICS_RESET_TESTS=true npm run test:e2e
```

`METRICS_URL` overrides the test URL. `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` selects an existing compatible Chromium. Browser tests create synthetic runs in the isolated runtime directory. The destructive reset test is skipped unless `METRICS_RESET_TESTS=true`; use that flag only with a dedicated test runtime root.

## API and references

`/api/runs`: multipart import/list; `/api/runs/from-runtimes`: seed import. Per-run endpoints provide state, start, phase approval, measurements, retry, cancel, SSE events, artifact list/preview/download, and download-all ZIP. `/api/linkedin/session` starts/checks authentication.

[Migration plan](docs/MIGRATION_PLAN.md) · [Source analysis](docs/SOURCE_ANALYSIS.md) · [Quarkus REST](https://quarkus.io/guides/rest) · [LangChain4j Agentic](https://docs.quarkiverse.io/quarkus-langchain4j/dev/agentic.html) · [Quinoa](https://docs.quarkiverse.io/quarkus-quinoa/dev/index.html).

The workbook-first pipeline update passes five additional Java regressions: structured sheet/row IDs without CSV reparse; stored/cached numeric values independent of currency, grouping, percentage and duration formatting; comma preservation and multiline attribution; complete no-URL metrics through all phases with conserved totals; missing/invalid inputs and correction; and protection against using current input evidence as a fresh trailing total. The two targeted workbook browser regressions also passed after this update.

Trailing input review passes four additional Java regressions covering original/effective value retrieval, legacy grouped-count prefill, keep/edit provenance and immutable originals, stale versions and resource scope, completion without invented lifetime totals/deltas, and explicit handling of missing inputs/baselines. The new mocked browser test passed for original numeric prefill, keep/edit requests, the separate fresh-total path, and mobile layout; TypeScript/Vite build passed.

Measurement-input prefill passes three additional Java tests for URL blog originals, latest manual values, metric-specific baseline/duration selection, optional or omitted notes, fresh trailing delta semantics, and existing row/type/length/numeric validation. Three browser regressions passed: current and trailing URL popups prefill 1000 and submit with a blank note, and the existing no-URL keep/edit workflow still works. TypeScript/Vite build passed.

## Structured processing and on-demand exports

Workbook preparation and all seven phases use structured `Data`/`Row` objects. Persisted sources, phase checkpoints, and immutable result versions are JSON; no input or phase CSV files are generated. `ResultStore` saves `output/<version>/<dataset>.json` and hashes those bytes for approval digests. Existing REST artifact names such as `digital.csv` identify the export format, not stored CSV files.

The SPA previews the selected JSON result version directly. Approvals, corrections, retries, and reload recovery use those structured datasets and immutable sources. CSV encoding/parsing is absent from the workbook agent pipeline. Explicit semicolon or line-break separation within Author/Tool cells remains an attribution rule; ordinary commas inside Excel text remain literal content.

Only a download request serializes CSV into memory: optional input snapshots, individual phase/final results, or all available results in a ZIP. Downloads create no files in the run folder. Export names, UTF-8 encoding, and the exact 24-column final schema remain compatible. Uploaded legacy CSV originals are preserved, and older saved CSV results/phase filenames remain readable without rewriting old runs. Reset still deletes all run-owned versions and uploads.

Endpoints remain unchanged. `GET /api/runs/{id}/artifacts/{name}/preview` reads structured rows, `.../download` exports that result version as CSV, `.../inputs/{name}/download` exports an immutable source snapshot, and `.../download-all` builds a ZIP in memory.

Verification for structured result storage: **66 Java tests passed**, including four new regressions covering all seven phases and historical keep/edit review without CSV files, immutable JSON versions and approval hashes, previews preserving sheet row IDs, on-demand input/result CSV and ZIP downloads with no disk changes, and compatibility with older CSV results and JSON phase filenames. TypeScript/Vite build and four mocked browser regressions passed, including the new structured preview/download flow and mobile layout. Phase 3 continues to create the full author × tool Cartesian product, allocate digital views or in-person attendance hours, and conserve totals; it stores structured results and keeps the existing CSV download names.

## Phase 2 attendance review

When Phase 2 publishes its in-person result, the SPA automatically selects `in-person.csv` for its structured preview, including after reload or recovery. Preview pagination resets to the first page on this transition. Polling and later versions preserve an explicitly selected different result; no CSV is generated by the preview.

Phase 2 approval remains required by RUN.md even when its issue count is zero. Zero issues means that the phase's collection/numeric validation passed; it does not verify the accuracy of submitted attendance, activity classification, or duplicates. The review panel now lists what to check: activity types and reporting-month dates, attendee counts, duration with the one-hour minimum, attendance-hours calculation, authors/tools before Phase 3 attribution, and booth duties retained in follow-up. An empty in-person result explicitly asks users to confirm that no eligible activities were submitted. Incorrect source values should be corrected in Excel and imported as a new run before approval. Existing blocking-issue and acknowledgment requirements remain enforced.

Every approval checkpoint now highlights **What changed from the previous phase** (or **since import** in Phase 1), including automatic classification, attendance calculation, attribution, and historical review changes. Phase 2/3 row-count comparisons use the previous phase's approved artifact version through the structured preview API, rather than assuming the immediately preceding version is the previous phase. If prior counts cannot be read, only current counts are shown with an availability note. Zero-issue phases explicitly state that no manual updates are flagged and explain why the user still confirms the automatic changes. The relevant result preview opens for each published phase: digital in Phases 1/3, in-person in Phase 2, historical in Phase 4, follow-up in Phase 5, and current in Phases 6/7. Manual preview choices remain intact during polling and reruns of the same phase. No approval gates or calculations were changed.

The **No manual updates flagged** status is emphasized with a bold, high-contrast green badge and a check-circle icon. It remains distinct from the amber issue-review status, is announced as a polite status update, and wraps within mobile layouts. This status does not replace the required approval or certify source-data accuracy.
