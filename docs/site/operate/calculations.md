# Calculation rules

The implemented metric model and saved evidence determine the result. Language-model commentary is optional and cannot invent attendance, views, dates, URLs or baselines.

## Attribution conserves the total

Explicit semicolon- or line-separated author and tool values form a Cartesian product. A resource with two authors and two tools has four attributed shares. Exact decimal allocation conserves the source total at six-decimal precision, including a remainder when needed.

Views shown in CSV output may be rounded up after allocation. Impact hours use exact values, so rounded display values are not an input to the calculation.

## Attendance and participation

| Activity | Metric basis |
|---|---|
| Standard participation | One participation unit per activity, shared across authors × tools |
| Ordinary in-person activity | Attendance × max(duration, 1 hour) |
| Confirmed monthly RH/IBM Sales User Experiences aggregates | Supplied completed monthly metric; duration is not reapplied |

For standard participation, attendance and duration are evidence rather than multipliers. With model weight 100, one activity shared by two authors and one tool yields 0.5 units and 50 impact hours for each author.

AppDev does not submit the sales-delivered customer-workshop exception. Markus and Elder blog supplements are one-time contributions, without a trailing comparison delta.

## Digital collection

| Source | Metric |
|---|---|
| YouTube | Views |
| LinkedIn posts/feed updates | Reactions |
| LinkedIn Live events | Video Views from post analytics |
| GitHub repositories | Stars |
| Terraform Registry | Downloads |
| DZone articles | Public Views counter |

LinkedIn Video Views are different from lifetime unique viewers and peak live viewers. DZone's abbreviated public display is approximate; expanded counts and collection evidence are saved as such.

## Trailing growth

Collect a cumulative resource total once, allocate it across its unique attributed rows, and subtract each attributed previous baseline once:

```text
attributed current total − attributed previous total = monthly growth
monthly growth × model weight = impact hours
```

Already attributed historical rows are not split a second time. Formula-proven legacy full-resource totals can be normalized into their original shares; inconsistent evidence requires correction. Duplicate physical rows do not create extra URL shares.

## Selected month and observation date

The reporting cutoff is the selected month's last day. Approved counts collected later are assigned to that selected reporting month, while `Measured at` preserves the real observation timestamp. The report does not claim the source was observed on the cutoff date.

Explicit verified workbook totals apply only to their verified reporting month. Separate delivery dates and activity stages prevent a similarly named activity from silently becoming a duplicate.

## Reference rows and exclusions

An eligible current contribution takes precedence over an overlapping trailing attribution. **Already counted** adds no second contribution. Retained historical or one-time input may show **No new contribution** rather than a fresh delta.

Unresolved actionable totals remain excluded until corrected. Source ineligibility, duplicate attribution, a missing model rule or an invalid baseline require their own correction; entering another total is not a universal fix.
