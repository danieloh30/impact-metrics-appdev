# Recovery and maintenance

Use the current state and saved evidence to decide what to retry. A retry keeps successful and manually entered measurements; a new run re-reads the source workbooks.

## Common recovery paths

| Symptom | Action |
|---|---|
| Deployment prints This command made no cluster changes | Generation succeeded; run with `--deploy` and your existing project namespace |
| Deployment cannot find Java or reports an older version | Select Java 25+ with `JAVA_HOME` or SDKMAN; the script also checks Homebrew JDK locations |
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

## History reset versus a new run

**Start another run** keeps all saved runs and prepares a new source snapshot. Use it for the same month or a later month.

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

For pending storage, inspect the PVC and storage-class binding mode. For pod startup failures, inspect image pulls, resource limits and volume permissions. The browser desktop uses `/tmp`; preserve its writable emptyDir even when the image root is read-only.
