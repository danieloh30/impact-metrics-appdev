For every Python call make sure to use a local Venv.

---

## RUN metrics pipeline — process notes

### Scripts and venvs

| Script | Venv | Purpose |
|---|---|---|
| `process_run.py` | `.venv` | Phase 1 + 2: classify rows, scrape YouTube/GitHub/Registry, compute attendance hours |
| `phase3_split.py` | `.venv` | Phase 3: cartesian-product split of multi-author × multi-tool rows |
| `phase4_trailing90.py` | `.venv` | Phase 4: same scraping logic applied to `trailing_90-day.csv` → `updated_90-day.csv` |
| `combine_run.py` | `.venv` | Phase 7: combine `digital.csv`, `in-person.csv`, and `follow-up.csv` → `current.csv` |
| `linkedin-reactions` skill | `.bob/skills/linkedin-reactions-main/linkedin-reactions/.venv` | Batch-scrapes total reactions from LinkedIn `/posts/` and `/feed/update/` URLs |

The `.venv` at the workspace root is created once with `python3 -m venv .venv` and requires no extra packages beyond the standard library (only `curl` and the GitHub/Registry public APIs are used).

---

### Input file encoding

`trailing_90-day.csv` is encoded in **Windows-1252** (cp1252), not UTF-8. Open it with `encoding="cp1252"` in Python. The current-month input file (`AvocadoBot 2.csv`) is UTF-8.

---

### LinkedIn scraping — two distinct URL types

LinkedIn URLs in the data fall into two categories that require different collection methods.

#### 1. Regular posts and feed updates (`/posts/` or `/feed/update/` URLs)

Use the `linkedin-reactions` skill:

```bash
.bob/skills/linkedin-reactions-main/linkedin-reactions/.venv/bin/python \
  .bob/skills/linkedin-reactions-main/linkedin-reactions/scripts/scrape_reactions.py \
  --csv <file>.csv \
  --url-column "Link / URL to resource"
```

The skill writes `total_reactions` and `reactions_scraped_at` columns. After review:
- For **Phase 4** (`updated_90-day.csv`): copy `total_reactions` → `Current total`
- For **Phase 1** (`digital.csv`): copy `total_reactions` → `Views / attendance`

#### 2. LinkedIn Live events (`/events/` URLs)

The skill **cannot** scrape these — event pages show viewer analytics, not a reaction count. Use **Video Views** (not "Total viewers" / lifetime unique viewers).

**Steps to collect Video Views manually:**

1. Navigate to the event URL: `linkedin.com/events/<event_id>/`
2. Click **"Show in-depth analytics"** on the overview panel
3. Locate the **"View post analytics"** link — use the selector `a[href*="post-analytics"]` (it renders as a `<p>` tag visually but is an `<a>` in the DOM):
   ```python
   href = page.locator('a[href*="post-analytics"]').get_attribute("href")
   # → https://www.linkedin.com/company/<company_id>/admin/post-analytics/urn:li:activity:<id>/
   ```
4. Navigate directly to that href
5. In the **"Video performance"** section, extract **Video Views** (NOT "Lifetime unique viewers")

**Why the three metrics differ:**

| Metric | Location | Meaning |
|---|---|---|
| **Video Views** ✅ | Post analytics → Video performance | Total play-starts — use this |
| Lifetime unique viewers | Event analytics overview | Deduplicated viewer count — do not use |
| Peak live viewers | Post analytics → Video performance | Max concurrent viewers |

---

### URL classification and scraping behaviour by type

| URL host | Method | Metric written |
|---|---|---|
| `youtube.com` / `youtu.be` | `curl` + regex on `"viewCount":"N"` | Views / attendance (Phase 1) / Current total (Phase 4) |
| `linkedin.com/events/` | Manual — post analytics admin page | Views / attendance (Phase 1) / Current total (Phase 4) |
| `linkedin.com/posts/` or `/feed/update/` | linkedin-reactions skill | total_reactions → copy to Views / attendance (Phase 1) or Current total (Phase 4) |
| `linkedin.com/pulse/` | linkedin-reactions skill | total_reactions → copy to Views / attendance (Phase 1) or Current total (Phase 4) |
| `github.com` | GitHub public API `api.github.com/repos/<owner>/<repo>` | Stars (Views / attendance) |
| `registry.terraform.io` | Registry API `registry.terraform.io/v1/providers/<ns>/<name>` | Downloads (Views / attendance) |
| Everything else | Flagged manual | — |

---

### Rows with no URL

Rows where `Link / URL to resource` is blank are flagged as `"No URL — manual collection required"` in the `Scrape notes` column. Views / attendance is left as-is from the input.

---

### LinkedIn event Video Views — values recorded

#### July 2026 run (current-month input)

| Entry | Event ID | Post analytics URN | Video Views |
|---|---|---|---|
| Getting into Terraform, Part 10: Policy as Code (Jul 7) | `7475986104123318272` | `urn:li:activity:7475986108028219392` | **1,459** |
| Getting into Terraform, Part 11: Drift Detection (Jul 21) | `7483569341720907776` | `urn:li:activity:7483569344527138817` | **1,128** |

#### August/September 2026 run

| Entry | Event ID | Post analytics URN | Video Views |
|---|---|---|---|
| Getting into Terraform, Part 1 | `7435396424625803265` | `urn:li:activity:7435396427599589376` | **2,186** |
| Getting into Terraform, Part 2 | `7437206691693244416` | `urn:li:activity:7437206694327336960` | **1,699** |
| Getting into Terraform, Part 3 | `7442967667042488320` | `urn:li:activity:7442967669567324160` | **1,690** |
| Getting into Terraform, Part 4 | `7445510622311788544` | `urn:li:activity:7445510625339846656` | **2,469** |
| Getting into Terraform, Part 5 | `7449484791475003392` | `urn:li:activity:7449484794549260288` | **2,592** |
| Getting into Terraform, Part 6 | `7457067763715641344` | `urn:li:activity:7457067765942665217` | **1,791** |
| Getting into Terraform, Part 10 | `7475986104123318272` | `urn:li:activity:7475986108028219392` | **1,508** |
| Getting into Terraform, Part 11 | `7486076077858201600` | `urn:li:activity:7486076080756248576` | **2,701** |

#### Trailing 90-day file — events requiring manual collection each run

The following LinkedIn event IDs appear in `trailing_90-day.csv` and must be re-scraped each run via the post analytics admin page:

| Event ID | Activity |
|---|---|
| `7435396424625803265` | Getting into Terraform, Part 1 (LinkedIn) |
| `7437206691693244416` | Getting into Terraform, Part 2 (LinkedIn) |
| `7442967667042488320` | Getting into Terraform, Part 3 (LinkedIn) |
| `7445510622311788544` | Getting into Terraform, Part 4 (LinkedIn) |
| `7449484791475003392` | Getting into Terraform, Part 5 (LinkedIn) |
| `7457067763715641344` | Getting into Terraform, Part 6 (LinkedIn) |
| `7475986104123318272` | Getting into Terraform, Part 10 (LinkedIn) |
| `7486076077858201600` | Getting into Terraform, Part 11 (LinkedIn) |
