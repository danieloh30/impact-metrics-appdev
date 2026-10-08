# RUN metrics calculation process - current month inputs

## Scripts

The following Python scripts implement each phase. All scripts use the local `.venv` virtual environment.

| Script | Phase |
|---|---|
| `process_run.py` | Phase 1 (digital scraping) + Phase 2 (in-person calculation) |
| `phase3_split.py` | Phase 3 (author/tool splitting) |
| `phase4_trailing90.py` | Phase 4 (trailing 90-day scraping) |

Run order:
```bash
.venv/bin/python process_run.py --dir <folder>        # produces digital.csv, in-person.csv, follow-up.csv
# → await approval, then:
.venv/bin/python phase3_split.py --dir <folder>       # splits digital.csv and in-person.csv in-place
# → await approval, then:
.venv/bin/python phase4_trailing90.py --dir <folder>  # produces updated_90-day.csv
```

All three scripts accept `--dir` (default: current directory). Pass the folder containing your input files and all outputs are written there too.

After `phase4_trailing90.py`, run the LinkedIn skill scraper to fill reactions for `/posts/` and `/feed/` URLs:
```bash
.bob/skills/linkedin-reactions-main/linkedin-reactions/.venv/bin/python \
  .bob/skills/linkedin-reactions-main/linkedin-reactions/scripts/scrape_reactions.py \
  --csv <folder>/updated_90-day.csv \
  --url-column "Link / URL to resource"
```

After the skill completes, copy `total_reactions` → `Current total` for all LinkedIn rows in `updated_90-day.csv`.

For LinkedIn Live event URLs (`/events/`), video views must be collected manually via the post analytics admin page. See `AGENTS.md` for the exact process.

---

## Phase 1: digital content metrics calculation

For every row in the input file where the type of activity is one of the following digital content types:

- 3rd party conference video
- Blog post / slide shares / .io updates
- Digital conference / event
- Livestream
- Podcast or webinar (internal / external)
- Recorded video
- Demos (archive / GitHub)
- Development - tools, add-ons (Registry)
- Development - tools (GitHub)
- Forum answer (IBM or practitioner community)
- Twitter / Facebook / LinkedIn / BlueSky / Weibo

### Automated scraping

Use `curl` to scrape the URL found in the Link / URL to resource column and extract the current viewership or engagement metrics.

Apply the following rules by URL type:

- **YouTube video:** extract `viewCount` from the page source and write it to the views / attendance column.
- **LinkedIn Live event (`/events/` URL):** navigate to the post analytics admin page and extract **Video Views** from the "Video performance" section. Do NOT use "Total viewers" (lifetime unique viewers) — see `AGENTS.md` for the full scraping process.
- **LinkedIn post / feed update (`/posts/` or `/feed/update/` URL):** use the `linkedin-reactions` skill to extract total reactions and write to the views / attendance column.
- **Podcast:** extract listens and downloads — flag if behind a login wall.
- **GitHub repository:** extract stars, watches, and forks via the GitHub API.
- **Terraform Registry provider:** extract total download count via the Registry API.
- **Forum thread:** extract views and reactions.
- **Twitter / Facebook / BlueSky / Weibo:** extract likes, shares, and comments.

Write the results out to a new CSV called digital.csv. Make sure to include all columns.

### Manual follow-up

Any row with a URL that cannot be scraped or has no metrics should be added to follow-up.csv; do not add to digital.csv.

### Before you move on to the next phase

- I must approve the digital.csv and follow-up.csv files before you can proceed to the next phase.

## Phase 2: in-person event calculation

If the type of activity is of the following:

- Booth duty
- Conference keynote or breakout (proprietary or 3rd party) 
- Conference workshop (proprietary or 3rd party)
- CSP or other partner presentation (digital or in-person)
- Customer meeting
- Customer workshop
- Customer presentation
- Internal team member enablement
- Meetup (user group)

These are considered in-person events.

Apply the following rules by activity type:

- **Booth duty:** copy the rows from the input file to follow-up.csv.
- Copy all of the other in-person entries from the input file to in-person.csv and a new column called "Attendance hours". Multiply the views / attendance by the duration in hours. If the duration in hours is less than 1 or blank, use 1. Input the result in Attendance hours.

### Before you move on to the next phase

- I must approve the in-person.csv and follow-up.csv files before you can proceed to the next phase.

## Phase 3: splitting of entries

In in-person.csv, digital.csv and follow-up.csv, if author of activity or tool has multiple inputs, separated by a semi-colon, split the entry into multiple rows.
Example: "Rosemary Wang;Nic Jackson" becomes two rows, one for each author.
Example: "HCP Terraform;Terraform" becomes two rows, one for each tool.
Based on the inputs above, the result should look like the following:

```
| Nic Jackson | Terraform | [Customer Meeting] Bureau of Fiscal Services - Terraform/Bicep Migration | Customer meeting | .25 |  |
| Nic Jackson | HCP Terraform | [Customer Meeting] Bureau of Fiscal Services - Terraform/Bicep Migration | Customer meeting | .25 |  |
| Rosemary Wang | HCP Terraform | [Customer Meeting] Bureau of Fiscal Services - Terraform/Bicep Migration | Customer meeting | .25 |  |
| Rosemary Wang | Terraform | [Customer Meeting] Bureau of Fiscal Services - Terraform/Bicep Migration | Customer meeting | .25 |  |
```

For digital.csv take the views and divide by the number of authors or tools. Write the output for the split views to the Views / attendance column.

For in-person.csv take the attendance hours and divide by the number of authors or tools. Write the output for the split attendance hours to the Views / attendance column.

For follow-up.csv where there is no view or attendance hours data, no calculation is required at this time.

### Before you move on to the next phase

- I must approve the in-person.csv, digital.csv and follow-up.csv files before you can proceed to the next phase.

## Phase 4: trailing 90-day calculation

trailing_90-day.csv file contains entries that require an additional trailing 90-day impact metric calculation. For each entry in this file, move the existing data in the Current total column to the Previous total column.

For each entry in this file, scrape the URL for current viewership metrics (refer to Phase 1) and add it to the Current total column. This represents the all-time viewership metric.

If there are duplicate URLs do not double scrape, take the total scraped metric, divide by the number of rows that contain that URL and update the Current total column.

Subtract the Previous total data from the Current total data and write the output to the Views / attendance column.

Write the results out to a new CSV called updated_90-day.csv. Make sure to include all columns.

After running `phase4_trailing90.py`, run the LinkedIn skill scraper against updated_90-day.csv (see the Scripts section above). LinkedIn Live event (`/events/`) URLs will still fail the skill — collect those manually via post analytics (see `AGENTS.md`).

### Before you move on to the next phase

- I must approve the updated_90-day.csv file before you can proceed to the next phase.

## Phase 5: Other in-person event calculation

If the type of activity is of the following:

- Analyst or press briefing
- External conference CFP panel
- Standard participation
- Whitepaper

These are considered other in-person events.

Add these rows to follow-up.csv.

## Phase 6: combine current month data for one export
After the individual files are updated, combine the data in the digital.csv, in-person.csv, and follow-up.csv into a new .csv file named: current.csv. This file should include the following columns:

Date,Author,Tool,Strategic initiative,Name of activity,Event,Collection,Type of activity,Trailing 90-day,Geography,Current total,Previous total,Views / attendance,Weight,Impact hours,Link / URL to resource,Notes / description,Column1,Column2,Scrape notes,total_reactions,total_comments,total_reposts,reactions_scraped_at

## Phase 7: writing Bob calculated metrics for current month
Once all data has been written to current.csv copy the values in the Views / attendance column and write to the Current total column.

## Out of scope:

### Manual follow-up calculation

Wait for me to prompt you to recalculate follow-up.csv based on the process in phase 1, 2 and 3.
I will reach out to Field Marketing manager for event where booth duty occured to gather total number of booth leads.
I will add total number of booth leads to the follow-up.csv file under views / attendance column.
Calculate and split based on the processes in Phase 1, 2 and 3, but do it all in follow-up.csv. Do not move entries to in-person.csv or digital.csv.
