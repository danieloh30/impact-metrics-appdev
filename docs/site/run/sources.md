# Source workbooks

The app prepares one immutable source snapshot for each run. A reporting month controls the current activities, historical window, and final reporting cutoff.

| Source | Purpose | Selection |
|---|---|---|
| **AvocadoBot 2.0 — AppDev DA impact inputs** | Current-month activities and matching Markus/Elder blog supplements | Current-month activity sheet |
| **AppDev Advocacy impact metrics 2026** | Attributed historical totals, baselines and the metric model | Latest eligible monthly inputs snapshot in the selected window |
| **Advocacy RUN metrics 2026** | Published monthly history for the channel and tool charts | Two tables on AppDev Advocacy |

The AvocadoBot default points to the AppDev workbook in the team's **Input files** location. Paste a different approved SharePoint workbook link when needed. One saved SharePoint session is reused for source reads and publication.

## Read and prepare

![Source workbook preparation with demo links](../assets/screenshots/source-preparation.png)
<p class="ops-caption">Demo workspace. Source links and workbook contents are illustrative.</p>

**Read workbooks & prepare inputs** reads both activity workbooks, loads saved chart history or reads it once, validates the sheet selection, and prepares the run. Successful snapshots are retained if a later step fails.

If more than one current activity sheet is eligible, preparation pauses for a sheet choice. Ambiguous historical snapshots require a source correction. A source inspection token expires after 30 minutes; re-read an expired source before preparing it.

## The trailing window

The implementation uses **three calendar months**, inclusive. A September run includes historical activity dates from July 1 through September 30. The app chooses one authoritative monthly snapshot to avoid duplicates from overlapping monthly sheets.

AvocadoBot's previous-month sheets are not the historical metrics source. The AppDev metrics snapshot supplies already attributed historical rows and comparison baselines.

## Published chart history

The channel and tool charts begin at September 2025 and end at the selected month. Published values for earlier months are retained; the selected month's published column is replaced by the newly calculated report.

The shared cache is stored in `runtimes/.chart-history/` and survives dev-mode restarts and **Reset run history**. Each run freezes its own history snapshot. **Refresh published chart history** updates the shared cache for new snapshots; it does not silently rewrite older runs.

!!! tip "Keep the provenance"
    The UI records source workbook names, selected sheets, checksums and date bounds. Use them when comparing reports. Similar totals from a workbook with a different historical window are not a replacement baseline.
