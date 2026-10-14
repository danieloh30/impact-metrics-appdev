# Recovery and maintenance

Use the current state and saved evidence to decide what to retry. A retry keeps successful and manually entered measurements; a new run re-reads the source workbooks.

## Common recovery paths

| Symptom | Action |
|---|---|
| Deployment prints This command made no cluster changes | Generation succeeded; run with `--deploy` and your existing project namespace |
| Deployment cannot find Java or reports an older version | Select Java 25+ with `JAVA_HOME` or SDKMAN; the script also checks Homebrew JDK locations |
| UI says Operation failed and logs say Runtime root must be a runtimes directory | Use the current profile with `METRICS_RUNTIME_ROOT=/data/metrics/runtimes`; preserve the PVC and verify `/api/runs` and `/api/chart-history` |
| Pod reports ImagePullBackOff / manifest unknown | Compare the BuildConfig output tag and pod image; use the current profile with the explicit OpenShift internal registry, then generate and deploy again |
| Source read fails or inspection expires | Reconnect if sign-in expired, then re-read the affected source |
| DZone or another source blocks collection | Retry unresolved collection, or enter a verified measurement |
| LinkedIn sign-in appears unfinished | Complete the browser sign-in and use Check LinkedIn connection |
| LinkedIn session is saved but metrics fail | Retry collection; verify access to the post/event analytics |
| Invalid attendance or baseline | Correct the specific field with source evidence |
| Negative reporting delta | Verify matching author/tool shares and the original baseline |
| Ambiguous publication attribution | Inspect the listed Excel rows; keep valid author/tool splits and remove only confirmed duplicate copies |
| Stale preview or workbook lock | Wait for Excel AutoSave to show Saved, close editing sessions, then preview and confirm again |
| Interrupted phase after restart | Use Retry phase to resume from saved inputs |

Reconnecting SharePoint does not release an editing lock. **Check workbook status** is a read-only metadata check; the previous failure remains an audit of that attempt until a fresh preview succeeds.

## Workbook size limits

If a source workbook exceeds the file-size limit, reduce unused images, sheets or formatting, or configure `METRICS_WORKBOOK_MAX_BYTES` (default `25000000`, 25 MB). The limit applies consistently to imports, chart history and publication. Expanded XLSX contents also have a 100 MB limit via `METRICS_WORKBOOK_MAX_EXPANDED_BYTES`; sheet and row limits remain in place. Restart dev mode after changing configuration, or update the deployment configuration and redeploy on OpenShift, then re-read the failed source. Existing runs and successful snapshots are kept; reconnecting SharePoint does not change the file-size limit.

### Columns outside the activity table

The limit counts 100 populated input fields, not the physical position of the last cell. Blank gaps and formatting do not count; a valid field far to the right retains its value and original source position. More than 100 populated fields or data outside the header extent triggers an error naming the source location. Preserve valid data rather than deleting columns indiscriminately. The reader never trims or rewrites the source workbook.

This check also applies to **Markus blog data** and **Elder blog data**. These supplements are validated before selecting their reporting-month rows, so a malformed blog sheet can block preparation even when it contributes no rows that month. The preparation panel shows the exact validation error next to the failed step. Correct and re-read the affected source under **Source details**, then retry; an expired snapshot requires re-reading both activity workbooks. Saved runs and chart history do not need to be reset.

If a blog table has been copied horizontally, the adapter verifies repeated columns across all rows. Identical populated values, cell types, formulas, cached results and date interpretation are counted once; `Source duplicate columns` records how many copies were collapsed in the derived data. Differing copies remain a blocking error naming the field, row and columns. The app does not choose a measurement or remove source columns. Correct conflicting copies in Excel and re-read AvocadoBot before retrying.

A missing selected-month tab in the historical metrics workbook is separate from this error. A first October run can use the completed September snapshot as its baseline. Current-input publication creates the October tab after Phase 3; no empty tab needs to be created manually.

## History reset versus a new run

**Start another run** keeps all saved runs and prepares a new source snapshot. Use it for the same month or a later month.

**Delete one run** with the trash button beside its entry in **Recent runs**. The confirmation identifies the run by date, reporting month when available, and ID. It removes only that run’s input copies, measurements and results. Other runs, saved sign-ins, published SharePoint workbooks and `.chart-history/` are kept. Download the run’s results first if needed. Wait for processing or publication to finish, or cancel the run, before deleting it.

**Reset run history** deletes saved runs, imported copies, measurements and output versions. It preserves the canonical instructions, saved sign-in sessions and `.chart-history/`. Download any results you want to keep before reset.

## Back up a cluster workspace

Finish or cancel active work before taking a consistent backup. Copy the PVC's `/data/metrics` contents to an approved protected destination. A full backup includes browser authentication state as well as workbooks and results; restrict access accordingly.

```bash
mkdir -p secure-backup
chmod 700 secure-backup
oc -n impact-metrics cp impact-hours-0:/data/metrics secure-backup/metrics
```

Restore into a compatible workspace with the correct volume permissions. Do not commit backups, `.sessions`, uploaded workbooks or generated run state to Git.

## Upgrade the application

1. Use a reviewed commit whose CI has passed.
2. Finish or cancel active runs and retain a protected backup.
3. Deploy with the same namespace and claim.
4. Re-open the tunnel, check saved runs, and reconnect a session only when requested.

Current packaged instructions replace the workspace's canonical templates at startup. Existing runs retain their frozen rule copies and source snapshots.

## Read-only cluster inspection

```bash
./deploy-openshift.sh --status --namespace impact-metrics
oc -n impact-metrics describe pod impact-hours-0
oc -n impact-metrics logs statefulset/impact-hours
```

For pending storage, inspect the PVC and storage-class binding mode. For pod startup failures, inspect image pulls, resource limits and volume permissions. Headless Chromium uses `/tmp`; preserve its writable emptyDir even when the image root is read-only.
