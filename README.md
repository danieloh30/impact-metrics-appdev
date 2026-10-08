# Impact Metrics AppDev

Quarkus LangChain4j application and React SPA for all seven RUN metrics phases. Upload the original multi-sheet `.xlsx` workbook to generate both CSV inputs, or import existing `AvocadoBot 2.csv` and optional `trailing_90-day.csv`, review phase approvals, enter verified measurements, preview results, and download CSVs or a ZIP. Production processing replaces Python with Java decimal calculations and specialized agents.

## Start locally

Requires Java 21+, Node 20.19+ or 22.12+, and npm. The Maven wrapper is included. On this Mac, `dev.sh` finds the installed Homebrew JDK when system Java is not configured.

```bash
cd /Users/danieloh/developer-advocacy/tools/impact-metrics-appdev
./dev.sh
```

Open **http://127.0.0.1:8097**. Quinoa builds and serves the SPA through Quarkus; no separate frontend server is needed. First startup requires dependency downloads. Override the port with `./dev.sh -Dquarkus.http.port=8098` if necessary.

Choose **Read workbook**, select the reporting month and source sheets, and choose **Prepare workbook inputs**. Alternatively, expand **Import existing CSV files instead** or choose **Load runtimes inputs**. Then accept the displayed calculation contract, and start processing. Approve phases 1–3 and optional phase 4; phases 5–7 then finish automatically. Invalid numeric values require correction. Unresolved collection issues require explicit acknowledgment. Download `digital.csv`, `in-person.csv`, `follow-up.csv`, optional `updated_90-day.csv`, and `current.csv` individually or as ZIP.

Restarted/interrupted runs become retryable and resume from immutable phase inputs. Successful measurements are cached per run. Approvals reference exact artifact versions; edits generate fresh versions.

## Excel workbook import

The original RUN.md assumes that `trailing_90-day.csv` already exists; it does not identify its creator or provide a creation phase. This application adds `WorkbookPreparationAgent`, a deterministic LangChain4j agent backed by the [Quarkus Apache POI extension](https://docs.quarkiverse.io/quarkus-poi/dev/index.html), to prepare the inputs from the original workbook.

1. Upload an unencrypted `.xlsx` workbook (maximum 6 MB) and choose **Read workbook**.
2. Review detected sheets, select the reporting month, and choose the authoritative current-month and historical activity sheets. The reporting month defaults to the current month; the month picker also accepts earlier months such as September 2026. The latest activity sheet is suggested as the current input. Select the separate Previous months inputs sheet for history. Avoid selecting duplicate copies of the same activities.
3. Choose **Prepare workbook inputs**, review the calculation contract, then start the existing phase agents. Original RUN approval gates are preserved.

| Input | Selection and preparation |
|---|---|
| `AvocadoBot 2.csv` | Activities dated within the selected reporting month from the selected current sheet |
| `trailing_90-day.csv` | All activities dated within the inclusive three-calendar-month window ending on the reporting month's last day, from the selected history sheets. Selection uses Date of activity (or Date), without filtering activity types or Trailing 90-day flags. |
| Previous baseline | Preserve the historical `Current total`; never substitute raw Views / attendance or zero. Multi-author × multi-tool submissions split their recorded baseline with conserved decimal allocation. Missing, invalid, or negative baselines block phase-4 approval until corrected with evidence. |

Examples: September 2026 selects July 1–September 30; October 2026 selects August 1–October 31. These are three calendar months, rather than exactly 90 elapsed days. Current-month input is selected separately from the current sheet. If the same sheet supplies both inputs, its reporting-month activities appear in both; the UI displays a warning. Choose authoritative separate current and historical sheets to avoid duplicate processing.

The workbook requires `Date of activity` (or `Date`), `Author of activity` (or `Author`), `Type of activity`, and `Link / URL to resource` headers in the first 30 rows of each selected sheet. Extra named columns are retained. Excel dates, ISO dates, and US M/D/YYYY dates are supported. Formula cells use saved cached values; recalculate and save in Excel first. The application never evaluates formulas, macros, or external links. Hidden sheets are excluded. Invalid dates, duplicate headers, Excel errors, and data without headers are rejected with a readable message. Limits: 100 sheets, 20,000 rows per sheet, and 100 columns.

If historical sheets contain only raw submissions without processed `Current total` values, the app can generate the trailing input, but those activities require verified baseline entry during phase 4. Fresh scraped totals cannot reconstruct historical totals. No trailing file is generated when no activities qualify.

Both generated CSVs use UTF-8 and are downloadable from **Prepared workbook inputs**. The original workbook is preserved as `rawdata/original.xlsx` under its run; filenames, sheet choices, reporting month, date window, SHA-256, and missing-baseline count are recorded in run state. All workbook data remains inside ignored `runtimes/runs/` storage.

Endpoints: `POST /api/workbooks/inspect` (multipart `workbook`), `POST /api/runs/from-workbook` (multipart `workbook`, `month`, `currentSheet`, and JSON array `historySheets`), and `GET /api/runs/{id}/inputs/{name}/download`. Input downloads allow only `AvocadoBot 2.csv` and `trailing_90-day.csv`; final exports retain their existing endpoints.

## Direct SharePoint workbook import

Choose **Import from SharePoint**, paste the IBM workbook link, then choose **Connect SharePoint**. A dedicated local Chrome window opens; complete IBM sign-in and MFA there. The app never asks for your password. When the connection status confirms sign-in, choose **Read SharePoint workbook**, select the month and sheets, and prepare the inputs through the same workbook agent flow.

This local workflow reuses the existing Playwright browser integration and needs no Entra application registration. It supports HTTPS `.xlsx` links on `ibm-my.sharepoint.com` using `/:x:/r/` or direct personal/site/team file paths. Other hosts and formats are rejected. Authentication does not grant additional file permissions; your IBM account must already be allowed to download the workbook. If organizational browser or download policies prevent it, download the original `.xlsx` through your approved browser and use **Upload Excel file**.

Chrome must be installed on the computer running Quarkus. Defaults use the installed Chrome channel and a separate profile under `runtimes/.sessions/sharepoint`, preserved by history reset and excluded from Git. Optional settings: `METRICS_SHAREPOINT_BROWSER_CHANNEL`, `METRICS_SHAREPOINT_BROWSER_EXECUTABLE`, and `METRICS_SHAREPOINT_ENABLED=false`. Browser sign-in is local to the Quarkus host; it is not an authentication flow for a remotely hosted server.

Inspection downloads one snapshot, held in a bounded in-memory cache for 30 minutes (maximum five snapshots). CSV preparation uses those exact bytes, consumes the snapshot, and saves `rawdata/original.xlsx`, its SHA-256, filename, and a canonical source URL without sharing-query tokens with the run. Restarting the app expires pending snapshots; read the workbook again. Browser download files are temporary under `runtimes/.uploads/` and are removed after ingestion. Models receive neither browser cookies nor full workbooks.

Endpoints: `GET/POST /api/sharepoint/session`, `POST /api/workbooks/from-sharepoint` with JSON `{"url":"..."}`, and `POST /api/runs/from-sharepoint` with multipart `sourceToken`, `month`, `currentSheet`, and JSON-array `historySheets`. An expired/consumed token returns 410. Signed-in access to the real IBM workbook must be verified interactively; automated tests use a synthetic download transport rather than IBM credentials.

References: [Playwright downloads](https://playwright.dev/java/docs/downloads) and [persistent browser contexts](https://playwright.dev/java/docs/api/class-browsertype#browser-type-launch-persistent-context).

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
    rawdata/           # original.xlsx and immutable/generated input CSVs
    instructions/     # frozen instruction snapshots
    state/            # atomic state, measurements, approvals, row lineage
    output/<version>/ # downloadable CSVs
  .sessions/          # optional LinkedIn browser profile
  .uploads/           # temporary multipart uploads
```

Git excludes runtime CSVs, every file under `runtimes/runs/`, sessions, uploads, and future rawdata/output folders. Only `runtimes/RUN.md` and `runtimes/AGENTS.md` are versioned.

Filesystem access rejects traversal and symlink escapes. Current input and workbook-generated inputs are UTF-8; separately uploaded legacy trailing CSVs are Windows-1252; exports are UTF-8. Intermediate files retain source columns. `current.csv` uses the exact 24-column RUN schema.

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

## Collection & data review

A row appears in **Collection & data review** when the backend adds an entry to `run.issues`. The SPA displays the section whenever that list is nonempty. These are deterministic Java rules; model agents do not decide whether a row needs manual review.

| Condition | Review category | Approval behavior |
|---|---|---|
| During digital or trailing collection: missing URL, unsupported website, malformed URL, failed request, or unavailable metric | Manual collection | Enter a verified measurement, retry, or acknowledge unresolved collection |
| LinkedIn login/admin access unavailable, or reactions/Video Views cannot be extracted | Manual collection | Enter a verified measurement, retry, or acknowledge unresolved collection |
| In-person attendance is blank, nonnumeric, or negative | Correction required | Must correct before approval |
| Duration is supplied but nonnumeric | Correction required | Must correct before approval |
| Trailing baseline is blank, nonnumeric, or negative, even before a fresh measurement is available | Correction required | Must correct before approval |

Missing in-person duration defaults to one hour. Manual entries require a nonnegative numeric value, the expected metric type, and a source or collection note. Entering a measurement regenerates the phase artifacts and creates a new version for approval. Collection issues are scoped to the current phase; trailing collection reports one collection issue per shared URL group.

Currently, booth duty and other/unknown activity types go into `follow-up.csv` without appearing in this review section. Negative trailing deltas receive a CSV review note but do not create a review-panel issue.

Implementation: [review panel](src/main/webui/src/main.tsx), [phase issue rules](src/main/java/com/ibm/advocacy/service/Pipeline.java), [provider collection failures](src/main/java/com/ibm/advocacy/collector/MetricCollectors.java), [LinkedIn collection](src/main/java/com/ibm/advocacy/collector/LinkedInCollector.java), and [approval/manual-entry validation](src/main/java/com/ibm/advocacy/service/RunService.java).

## Verification

Passed **36 Java tests**, TypeScript/Vite build, and **5 browser tests** through the Quarkus-served SPA. Browser tests cover Excel upload, editable reporting month and calendar windows, generated input downloads, synthetic SharePoint sign-in/snapshot UI with real workbook processing, both CSV inputs, manual collection, four approvals, all five downloads, ZIP, exact final schema, reload recovery, mobile layout, confirmed history reset, and reset notification across open tabs. Java workbook/source tests verify date boundaries, all dated history rows, baseline allocation and correction, cached formulas, Unicode, source validation, snapshot reuse/consumption, source provenance, and HTTP 400 validation through agent wrappers. Java reset tests verify seed/session preservation, required confirmation, active-worker rejection, symlink boundaries, and normal live-stream completion after deletion. Fixtures are synthetic, not live impact measurements. Live IBM SharePoint sign-in and the actual shared workbook have not been verified; sign in through the app to exercise that access. Frontend audit reported zero vulnerabilities.

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
