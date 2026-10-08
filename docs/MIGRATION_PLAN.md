# Quarkus LangChain4j migration plan

Prepared 2026-10-07. Implementation delivered: Quarkus backend, LangChain4j agent workflow, React SPA, all seven processing phases, persistent approvals, manual corrections, and CSV/ZIP downloads. Verified with 22 Java tests and a browser end-to-end test. See README.md for startup and collection limits. This document preserves the original implementation plan; optional model review and authenticated LinkedIn collection require configuration and have not been live-verified.

## Outcome and scope

Build a local Quarkus application with a React SPA where the user uploads `AvocadoBot 2.csv`, optionally uploads `trailing_90-day.csv`, starts RUN processing, reviews each required phase, enters missing measurements, and downloads the resulting CSVs.

Project root: `/Users/danieloh/developer-advocacy/tools/impact-metrics-appdev`.

Business instructions: `runtimes/RUN.md` and `runtimes/AGENTS.md`. Agents consume those documents for the active run. Processing is limited to this project's `runtimes` directory. There is no arbitrary filesystem picker or processing of the original project's ai/devops datasets.

Outputs: `digital.csv`, `in-person.csv`, `follow-up.csv`, `updated_90-day.csv`, and `current.csv`. Updated-90 is available only when its input has been supplied. Current.csv contains current-month data only; trailing data remains a separate export.

## Recommended architecture

Use Java 21, Maven, Quarkus REST with Jackson, REST Client, Hibernate Validator, Quarkus LangChain4j Agentic, one configurable model-provider extension, Apache Commons CSV, and Quinoa with React/TypeScript/Vite. Pin compatible released versions in milestone 1 and validate them together; this plan intentionally does not invent a version combination.

Quarkus LangChain4j supports declarative sequence, parallel, and conditional workflows, shared AgenticScope state, and plain Java agents. These support specialized collection/review agents alongside deterministic processing. [Official Agentic AI documentation](https://docs.quarkiverse.io/quarkus-langchain4j/dev/agentic.html).

Quinoa integrates frontend builds into Quarkus and documents Vite setup. Package the SPA and backend together, with SPA routing excluding `/api` and downloads. [Official Quinoa framework documentation](https://docs.quarkiverse.io/quarkus-quinoa/dev/web-frameworks.html).

Keep orchestration control in a durable Java state machine. Each approval-bounded segment invokes a LangChain4j workflow; long human waits must not hold an HTTP request, LLM call, thread, or in-memory future open. Persist run state and resume by run ID after restart. Additional workflow infrastructure such as Quarkus Flow is optional future work, not necessary for the first app.

### Agents and responsibilities

1. **InstructionAgent** reads the active instruction snapshots and proposes a structured RunContract: phase sequence, column mappings, metric policy, and approvals. Java validates it against the implemented contract. Unsupported instructions produce a review-required state; they do not enable arbitrary code or tools.
2. **ClassificationAgent** invokes a deterministic classifier for the listed activity types. It can explain unknown types or propose a mapping for review; it cannot silently redefine known types.
3. **DigitalMetricsAgent** routes allowed public URLs to typed collection tools and returns Measurement records plus evidence. The same collectors serve current-month and trailing-90 workflows.
4. **LinkedInMetricsAgent** distinguishes posts/feed/pulse from events and invokes an authenticated Java browser collector where supported. Failed collection becomes a manual task. Events always require fresh Video Views from post analytics.
5. **AttendanceAgent** delegates to Java attendance arithmetic and produces validation issues for malformed numbers.
6. **AttributionAgent** delegates cartesian author/tool splitting and metric allocation to Java.
7. **Trailing90Agent** delegates baseline preservation, one-fetch-per-URL collection, allocation across duplicate rows, and delta calculation to Java.
8. **ValidationAgent** combines deterministic integrity checks with an LLM explanation of unresolved cases. Deterministic failures block approval regardless of the model's assessment.
9. **ExportAgent** calls the Java export service after the state machine authorizes it. Only the export service writes CSVs.

Use explicit sequence/conditional workflows for required phases. Collect independent URL groups concurrently with bounded workers and unique scope output keys; merge immutable results in Java. Agents exchange run IDs, row IDs, typed result summaries, and bounded instruction context. Keep full CSVs in Java storage to avoid sending personal columns or every cell through the model.

LLMs interpret instructions and explain collection/review results. All numerical calculation, routing of known activity types, persistence, approvals, and serialization are reproducible Java operations. Model outages must preserve run state and allow retries/manual data entry; outputs must never depend on invented measurements.

### Core Java services and types

Implement `RuntimeWorkspace`, `InstructionRepository`, `CsvCodec`, `ActivityClassifier`, `MetricCollectorRegistry`, `AttendanceCalculator`, `AttributionService`, `Trailing90Calculator`, `RunOrchestrator`, `ApprovalService`, `RunRepository`, `ValidationService`, and `CsvExportService`.

Core types: `RunContract`, `RunContext`, `SourceRow`, `Measurement`, `CollectionIssue`, `ArtifactVersion`, `PhaseReview`, and `ApprovalRecord`. Measurement includes metric type, decimal value, source URL, collection timestamp, status, and evidence reference. Internal row lineage maps each output row to its source row and attribution combination; keep metadata outside the required CSV schema.

## Runtime directory contract

Both raw data and generated output belong under `runtimes`. Improving its internal organization is deferred to implementation; the layout below is a provisional design, not a completed folder migration.

The later improvement should separate `rawdata/` from `output/` within each isolated run, preserve original uploads unchanged, version generated CSVs, and keep instructions, state, and collection evidence separate from downloadable output. The SPA should resolve inputs and downloads through run/artifact IDs so users do not depend on the physical folder layout. Define retention and cleanup rules before deleting any raw data or previous outputs.

Keep the currently copied seed CSV files in place until that migration is implemented. Only the internal structure changes; the runtimes-only processing boundary remains mandatory.

Proposed later layout:

```text
impact-metrics-appdev/
  README.md
  docs/MIGRATION_PLAN.md
  docs/SOURCE_ANALYSIS.md
  pom.xml                         # created in implementation milestone 1
  src/main/java/...               # backend, services, agents
  src/main/resources/...
  src/main/webui/...              # React SPA
  src/test/...                    # synthetic test fixtures
  runtimes/
    RUN.md
    AGENTS.md
    AvocadoBot 2.csv              # copied seed input
    trailing_90-day.csv           # copied seed input
    runs/<run-id>/
      instructions/RUN.md
      instructions/AGENTS.md
      rawdata/AvocadoBot 2.csv
      rawdata/trailing_90-day.csv
      state/run.json
      state/approvals.json
      evidence/...
      output/<version>/digital.csv
      output/<version>/in-person.csv
      output/<version>/follow-up.csv
      output/<version>/updated_90-day.csv
      output/<version>/current.csv
```

At startup resolve and validate the fixed runtime root. Every production path must remain within that root after normalization and real-path checks. Reject traversal, absolute upload names, symlink escapes, and nonallowlisted artifact names. The server supplies run IDs and canonical filenames. Runtime JSON state is atomically replaced under a per-run lock; recovery does not refetch already successful measurements.

An import copies instruction files into the run and hashes instructions plus input bytes. Instructions remain frozen for that run. An updated instruction file requires a new run/contract review. Build and dependency files live outside runtimes; operational agents receive no general shell, filesystem, or unrestricted HTTP tool.

HTTP collectors support only the approved provider hosts/routes, validate redirects, block private/loopback destinations, impose response-size/time limits, and back off on rate limits. This follows directly from importing URLs from CSVs.

## Processing contract and SPA checkpoints

### Import and prepare

The SPA offers required current-month upload and optional trailing-90 upload, then displays encoding/header validation and row counts. It also offers an explicit Load runtimes inputs action for the copied seed inputs. Importing does not automatically run scraping.

Decode current-month input as UTF-8 with optional BOM; decode original trailing input as Windows-1252. Generated outputs are UTF-8. Normalize nonbreaking spaces and surrounding header whitespace; map known aliases such as Date of activity → Date and Author of activity → Author at final export. Preserve unknown named columns in intermediate CSVs. Reject header collisions and malformed row widths with actionable errors. Retain populated unnamed columns under generated names.

### Phase 1: digital collection

Process only defined digital activity types. Successful collection writes Views / attendance. Unsupported URLs, failed collectors, login walls, and blank URLs route exclusively to follow-up with preserved incoming values and a reason. LinkedIn measurements pending authentication stay in follow-up rather than appearing as successful digital rows.

Collection adapters:

- YouTube: parse the public page's viewCount with an explicitly tested fallback. Fail clearly if unavailable.
- GitHub: public repository API; stars are the impact measurement, with additional counters retained as evidence.
- Terraform Registry: provider API; download count is the impact measurement.
- LinkedIn posts/feed/pulse: fresh total_reactions only; supporting comments/reposts remain separate fields.
- LinkedIn events: event overview → Show in-depth analytics → `a[href*="post-analytics"]` → Video performance → Video Views. Missing admin access requires manual input with measurement type, URL, and timestamp. Lifetime unique viewers and peak concurrent viewers are rejected as substitutes.
- Other sites: manual tasks initially. Add adapters later only when a reliable metric and collection method are specified.

The Java Playwright collector uses a dedicated authenticated browser session, stored under runtimes and excluded from exports/source control. Port the existing LinkedIn scraper logic rather than depending on the Python skill in the finished app. Pulse support must be independently verified; login or unavailable metrics always permit manual collection. Never hardcode historical event IDs or values.

Persist phase-1 artifacts and require **Approve digital and follow-up** before phase 2. Manual digital collection entered before approval may resolve a phase-1 task and route the row to digital; later booth/follow-up recalculation is a separate explicit operation.

### Phase 2: attendance

Booth duty joins follow-up without automatic lead calculation. For each other listed in-person type, `Attendance hours = attendance × max(duration, 1)`. Blank/sub-hour duration defaults to 1. Invalid attendance is a review issue, not a measured zero. Decimal arithmetic uses BigDecimal. In-person events do not require a URL.

Persist in-person and updated follow-up artifacts and require **Approve in-person and follow-up**.

### Phase 3: attribution

Split semicolon-delimited authors and tools, trim empty segments, and use the full cartesian product. A blank author/tool retains one blank component. Divide digital Views / attendance by authorCount × toolCount. Divide original in-person Attendance hours by the same product and write the allocated result to Views / attendance; Attendance hours also records the allocated value to avoid repeating the full total on every attributed row.

Split follow-up identity fields while preserving unresolved metrics without calculating them. Retain original metric and divisor in internal lineage for any separately requested later calculation, avoiding repeated division.

Use a documented six-decimal export scale with deterministic remainder assignment to the final combination so exported values sum to the original total. This is a proposed precision policy; legacy scripts round to four decimals without conserving the remainder. Show the policy as part of initial contract review.

Persist all three split files and require **Approve split files**. Re-running this phase reads its immutable unsplit inputs, preventing double division.

### Phase 4: trailing-90

If no trailing input was supplied, mark phase 4 SKIPPED with a reason and continue current-month processing. Otherwise, for each row copy original Current total to Previous total before collection.

Group nonblank URLs using a documented conservative normalization (trim, normalize host, discard fragments; preserve potentially meaningful path/query parameters). Fetch each group once, including LinkedIn. Divide the new total by the group's row count, then compute each row's `Views / attendance = allocated Current total − Previous total`.

Example: two URL-sharing rows previously hold 40 and 40. A new total of 120 becomes Current total 60 on each row, Previous total 40, and delta 20 on each row.

Failed collection preserves the last known Current total for review, leaves the new delta uncomputed, and records stale/unresolved status; it must not present an old value as newly scraped or manufacture a zero delta. Negative observed deltas remain visible and flagged. Manual collection updates the group total before allocation, then recalculates deltas once. Blank URLs are never grouped together as one resource.

Persist updated_90-day.csv and require **Approve trailing-90 export**. Approvals may acknowledge explicitly unresolved rows but cannot approve malformed data or arithmetic failures.

### Phases 5–7: finish

Phase 5 appends Analyst or press briefing, External conference CFP panel, Standard participation, and Whitepaper to follow-up. Because this occurs after phase 3, split their author/tool identity on append, without an unrequested follow-up metric calculation. Reuse source row IDs so retrying cannot duplicate them. Unknown activity types are retained in follow-up with an issue.

Phase 6 concatenates the latest approved/transformed current-month files into current.csv. Missing required phase artifacts are failures, not silent skips. Phase 7 sets Current total to Views / attendance for each current.csv row, including blank values. It does not add updated_90-day.csv.

Final current.csv header order is exactly:

```text
Date,Author,Tool,Strategic initiative,Name of activity,Event,Collection,Type of activity,Trailing 90-day,Geography,Current total,Previous total,Views / attendance,Weight,Impact hours,Link / URL to resource,Notes / description,Column1,Column2,Scrape notes,total_reactions,total_comments,total_reposts,reactions_scraped_at
```

Keep the fixed schema; extra source columns remain accessible in intermediate artifacts. No new weight/Impact hours calculation is authorized by these instructions. Export any existing mapped values without inventing a formula.

## SPA and API design

The SPA has an import panel, phase timeline, artifact preview tabs, a manual measurement queue, an approval panel, and downloads. Preview pages show row counts, unresolved rows, Scrape notes, and relevant before/after values. CSV cells render as text. Users can download phase artifacts while a run awaits approval; label them with phase/version/status. Completed artifacts have separate download buttons plus Download all as ZIP.

Run states: CREATED, VALIDATING, READY, RUNNING_PHASE, AWAITING_APPROVAL, FAILED_RETRYABLE, FAILED, CANCELLED, COMPLETED. Missing measurements are tracked as issues per phase, not an ambiguous global success state.

Planned endpoints:

- `POST /api/runs`: multipart CSV import; returns run ID and validation summary.
- `POST /api/runs/from-runtimes`: explicitly copies the fixed seed inputs into a new run.
- `GET /api/runs/{id}`: phase state, issues, instruction hashes, artifact versions.
- `POST /api/runs/{id}/start`: starts the first phase asynchronously; returns 202.
- `GET /api/runs/{id}/events`: SSE phase/agent progress; reconnect uses persisted sequence IDs.
- `GET /api/runs/{id}/artifacts`: artifact names, row counts, checksums, versions, status.
- `GET /api/runs/{id}/artifacts/{name}/preview`: paginated structured preview for a specified version.
- `GET /api/runs/{id}/artifacts/{name}/download`: CSV attachment for a specified version.
- `GET /api/runs/{id}/download-all`: ZIP of available requested artifact versions.
- `POST /api/runs/{id}/measurements`: typed row/group measurement with provenance and phase version.
- `POST /api/runs/{id}/phases/{phase}/approve`: approves an exact artifact hash/version and records acknowledged issues.
- `POST /api/runs/{id}/retry`: retries failed work without reprocessing completed source rows.
- `POST /api/runs/{id}/cancel`: stops new work and preserves artifacts.

Any edit regenerates the phase artifact version and invalidates its prior approval. State transitions reject stale approvals, duplicate transitions, and unauthorized later-phase starts. After downstream work has begun, require a new run or explicit rollback to an immutable phase input instead of editing already approved history in place.

## Implementation sequence

1. **Freeze the contract and bootstrap.** Select compatible released dependencies and model-provider configuration. Implement CSV codec, runtime boundaries, instruction hashing, typed contract review, and synthetic fixtures. Confirm source-analysis choices through the first-run contract review.
2. **Deliver the first vertical slice.** Improve the runtimes layout for immutable rawdata and versioned output, with an explicit migration of the seed inputs and stable SPA artifact references. SPA imports current-month CSV; Java classifies rows, uses deterministic/mock collection fixtures for development, and calculates phase outputs; SPA previews/downloads digital, in-person, follow-up and performs separate phase approvals. Never mark mock metrics as live collection.
3. **Implement attribution and current export.** Port phases 3, 5, 6, 7; enforce row lineage and conservation; verify the 24-column current.csv contract.
4. **Add shared public collectors and agents.** Wire real YouTube/GitHub/Registry adapters into the LangChain4j agents, instruction review, bounded collection execution, evidence, progress, retries, and manual fallback. The agent workflow must call the Java services from earlier milestones.
5. **Add trailing-90 and LinkedIn.** Implement Windows-1252 import, grouped allocation/deltas, Java browser collection, and fresh manually verified event Video Views fallback.
6. **Finish recovery and SPA validation.** Verify restart/resume, approval invalidation, concurrent run isolation, artifact ZIP download, and all edge cases below. Run the copied runtimes fixtures with controlled collectors before comparing an explicitly approved live run.

Each milestone ends in working code and its relevant checks. Final acceptance requires all six; a static UI or model-written CSV alone does not satisfy the request.

## Acceptance checks

- Import the copied 29-row current-month file without losing any source row. Expected initial classification is 5 digital, 22 regular in-person, and 2 other activities; successful collection determines digital versus follow-up placement.
- Use controlled collector fixtures to verify outputs; do not demand equality with live historical view counts or preserve known script defects.
- Two authors × two tools produce four rows; digital 100 becomes four allocations of 25. In-person attendance 25 × duration 2 gives 50 hours, then four Views / attendance values of 12.5.
- Three-way and other nonterminating decimal splits preserve their exported sum under the explicit remainder policy.
- Blank duration and 0.25 duration use one hour. Blank/invalid attendance raises an issue; explicit zero remains valid.
- Unresolved digital source rows appear only in follow-up. Unknown types and phase-5 rows are not dropped; no repeated follow-up recalculation occurs.
- LinkedIn post reactions 10, comments 3, reposts 2 produce impact 10. An event uses Video Views, including values below 100 and zero; authentication failure cannot substitute another metric.
- On a controlled trailing fixture, one shared URL is fetched once, totals are allocated once, baselines are preserved, and deltas match. The 48-row seed file's 11 duplicate URL groups exercise grouping.
- Windows-1252 accents, BOMs, nonbreaking header spaces, quoted commas, embedded newlines, blank unnamed headers, and header collisions receive correct handling.
- Current.csv has exactly 24 columns in the required order and Current total equals Views / attendance for every row. It excludes trailing-90 rows.
- No phase advances before its approval. Stale artifact hashes are rejected; a restart retains the waiting phase; retry does not double split, double subtract, or duplicate rows.
- Concurrent runs cannot overwrite each other's files. Path traversal and symlink uploads cannot escape runtimes. Downloads contain only the requested run's allowlisted artifacts.
- A browser end-to-end check imports files, starts processing, observes progress, enters a manual measurement, approves required phases, and downloads parseable CSVs/ZIP. Validate the packaged Quarkus SPA, not only a separate frontend dev server.

## Operational limits and later work

LinkedIn requires an authorized logged-in browser and can change markup; manual collection is an essential working path. Unsupported blog, forum, podcast, and social resources remain explicit manual tasks until collectors are added. One configured LLM provider is sufficient initially; credentials belong in environment/configuration, outside CSV downloads.

The completed app replaces Python production execution. Python may be used only for optional comparison tooling through a local venv, per the supplied instructions. No deployment, weight calculation, automatic booth-lead gathering, processing of sibling datasets, or unrequested follow-up recalculation is part of this migration.
