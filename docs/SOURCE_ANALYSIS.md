# Existing RUN pipeline analysis

Reviewed on 2026-10-07 against `/Users/danieloh/developer-advocacy/tools/impact-metrics`.

## Business contract

RUN.md defines seven business phases:

1. Collect digital metrics; successful rows go to `digital.csv`, unresolved rows go to `follow-up.csv`.
2. Calculate in-person attendance hours; booth duty goes to follow-up.
3. Split authors and tools across all three current-month files.
4. Refresh trailing-90 totals and calculate their change from the previous baseline.
5. Add other in-person activity types to follow-up.
6. Combine the three current-month files using the specified 24-column schema.
7. Copy `Views / attendance` to `Current total` in `current.csv`.

Approval is required after phase 1, phase 2, phase 3, and phase 4. The instruction examples group phases 1 and 2 in one script invocation, but the prose calls for separate approvals. The proposed app implements separate checkpoints to satisfy the stricter contract. Approval of phase 1 and 2 includes the corresponding version of follow-up.csv.

Follow-up recalculation after booth lead collection is explicitly outside the initial workflow. It must be a separately requested operation that keeps those rows in follow-up.csv.

AGENTS.md supplies collection details, Windows-1252 decoding for trailing-90 input, and the distinction between LinkedIn reactions and LinkedIn Live Video Views. Recorded historical LinkedIn values are examples, not fresh measurements.

## Observed script gaps

- `process_run.py:166–225`: unresolved digital rows are appended to both digital and follow-up, contrary to RUN.md's exclusive routing. Combining these files can double count activity.
- `process_run.py:197–202`: LinkedIn rows are added to digital before a measurement succeeds; event URLs are not distinguished from post URLs.
- `process_run.py:219–223`: phase 1 only automates YouTube. GitHub and Registry collection exist in phase4_trailing90.py but are not reused here.
- `process_run.py:266–285`: attendance uses floating-point arithmetic and silently converts invalid attendance to zero. RUN.md only explicitly specifies the duration default, so invalid attendance should be flagged rather than treated as a measured zero.
- `process_run.py:329–338`: phase 1 and phase 2 run together. Printed approval prompts do not enforce approval.
- `phase3_split.py:100–116`: only digital and in-person files are split. Follow-up is omitted.
- `phase3_split.py:115–116`: in-person splitting writes divided values into `Attendance hours`, leaving `Views / attendance` unchanged. RUN.md requires the split attendance-hours value in `Views / attendance`.
- `phase3_split.py:65–74`: blank or invalid metrics become zero and the split value rounds to four decimal places. The new contract must distinguish blank, zero, and invalid data and explicitly conserve totals under its documented rounding rule.
- `phase4_trailing90.py:183–307`: URL caching avoids repeat fetches, but copies the full total to every duplicate row. It does not divide by the number of rows sharing the URL, move old Current total to Previous total, or recompute Views / attendance as the delta.
- `process_run.py:137–160`, `phase4_trailing90.py:151–177`, and `apply_linkedin_totals.py:66–81`: sum reactions, comments, and reposts. RUN.md and AGENTS.md direct copying total_reactions alone.
- `apply_linkedin_totals.py:14–29`: hardcodes `devops/updated_90-day.csv` and historical event values, unsuitable for a runtimes-only fresh collection workflow. It also does not recompute deltas after updating totals.
- `scrape_linkedin_events.py:145–176`: hardcodes a list of event IDs and a Python browser profile; collection should use the URLs in the active run. Its Video Views extraction rejects counts below 100, which can exclude valid measurements.
- `combine_run.py:77–123`: combines available files and maps the target schema but never implements phase 7's Current total copy. Missing required files are skipped, which could produce an incomplete final export.
- Phase 5's four other in-person types have no handling in process_run.py, so their rows are omitted from its outputs.
- `run_metrics.sh` has a separate phase order, positional columns, a different trailing filename (`trailing 90-day.csv`), and direct system Python calls. It is not a reliable migration baseline.
- The existing `.venv/bin/python` resolves through Python 3.9 to a missing `/usr/local/opt/python@3.9/bin/python3.9`. No processing scripts were run during this review.

These findings describe script behavior; they do not establish that every stored output is incorrect. Older outputs may have been manually corrected.

## Runtimes input inventory

Read-only parsing of nonempty CSV records found:

- `AvocadoBot 2.csv`: 29 rows, 17 named columns, 5 digital activities, 22 regular in-person activities, and 2 other activities (Standard participation and External conference CFP panel). There are 20 blank resource URLs across all activity types; a missing URL does not prevent ordinary in-person attendance calculation.
- The current-month digital URLs include DZone, YouTube, and developer.ibm.com. Only two are YouTube URLs. Manual follow-up is therefore necessary even with working public collectors.
- `trailing_90-day.csv`: 48 rows, 15 named columns plus trailing unnamed columns; 11 duplicate nonblank URL groups; 4 blank URLs. Hosts include YouTube, LinkedIn, DZone, x.com, SIGS, an IBM internal resource, and The Main Thread.
- The runtimes trailing file has six LinkedIn URLs. The instruction notes also contain Terraform event examples from other runs. The app must enumerate actual runtimes URLs rather than process every event ID listed in those notes.
- Existing runtime outputs include digital, in-person, follow-up, and updated-90 CSVs; current.csv is absent. Outputs were not rewritten or copied as new results.

## Contract choices for implementation

Use the written business instructions as the acceptance baseline, with explicit proposed interpretations:

- Digital and follow-up are disjoint. Preserve incoming values for unresolved rows and explain the collection failure in Scrape notes.
- Copy LinkedIn total_reactions alone; keep comments/reposts as supporting columns.
- GitHub impact uses stars, following AGENTS.md; retain other fetched counters as evidence without adding them to the metric.
- Author/tool split divisor is the product of author and tool counts, following RUN.md's cartesian example.
- Follow-up rows without verified metrics split names/tools without inventing zero or performing a metric calculation. Existing unverified attendance remains preserved; later recalculation is an explicit separate request.
- In-person split output uses attendance-hours allocation in Views / attendance. Keep the original attendance calculation and attribution evidence internally.
- Negative trailing deltas are preserved and flagged for review; the instructions do not authorize clamping to zero.
- Empty unnamed trailing columns can be discarded; populated unnamed columns receive stable generated names so their content is retained.
- Unknown activity types go to follow-up with a reason rather than disappearing.

The first implementation milestone freezes these interpretations into a versioned contract and presents them in the SPA before the first run. That contract records every difference from the legacy scripts; it does not silently rewrite the source instructions.
