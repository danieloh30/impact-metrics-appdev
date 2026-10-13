---
hide:
  - toc
---

<div class="ops-hero" markdown>

<div class="ops-eyebrow">APPDEV / PLATFORM OPERATIONS</div>

# Run the agents.<br>Report the impact.

Turn workbook activities into traceable monthly impact hours. Prepare the sources once, let clean phases advance automatically, and review only the items that need your decision.

[Run locally](run/local.md){ .md-button .md-button--primary }
[Deploy on OpenShift](deploy/openshift.md){ .md-button }

<div class="ops-stack">QUARKUS 3.40 · JAVA 25 · LANGCHAIN4J AGENTIC · PLAYWRIGHT</div>

</div>

<div class="grid cards ops-cards" markdown>

- :material-play-circle-outline: **Run a monthly report**

    ---

    Connect source workbooks, choose a reporting month, and follow live collection and review checkpoints.

    [Start the workflow →](run/workflow.md)

- :material-chart-areaspline: **Understand the result**

    ---

    Inspect activity and tool contributions, compare published history, and export editable Excel pivots and charts.

    [Use the reporting outputs →](run/reports.md)

- :material-server-outline: **Operate a workspace**

    ---

    Deploy a persistent workspace with a private sign-in desktop, then manage sessions, recovery, and upgrades.

    [Open the deployment guide →](deploy/openshift.md)

</div>

## One pipeline, visible decisions

```mermaid
%%{init: {'look':'handDrawn','theme':'neutral','themeVariables': {'lineColor':'#4A4035'}}}%%
flowchart LR
  A([Source workbooks]) --> B([Prepare inputs])
  B --> C([Collect and attribute])
  C --> D([Review and publish inputs])
  D --> E([Collect trailing growth])
  E --> F([Generate pivots and charts])
  F --> G([Publish completed snapshot])
  G --> H([Download or start another run])

  style A fill:#D4E6F1,stroke:#2E6B8A
  style B fill:#D4E6F1,stroke:#2E6B8A
  style C fill:#E8E0F0,stroke:#6B5B8A
  style D fill:#E8DCC4,stroke:#6B5B45
  style E fill:#E8E0F0,stroke:#6B5B8A
  style F fill:#D8F0D8,stroke:#3D7A3D
  style G fill:#E8DCC4,stroke:#6B5B45
  style H fill:#D8F0D8,stroke:#3D7A3D
  linkStyle default stroke:#4A4035
```

Clean phases advance on the backend. Missing measurements, invalid data, and publication confirmations pause processing. The sticky monitor shows the active phase, real resource-check counts, and the next required action.

## A reporting workspace, not a spreadsheet script

The agents read structured Excel cells and preserve the original source bytes. Exact Java calculations govern attribution, baseline subtraction, and impact weights. Optional language-model commentary explains the workflow; it does not create measurements or change the arithmetic.

Results remain structured until you request a CSV or XLSX download. Run history, sign-in sessions, and published chart history live on persistent storage. A new run can reuse the same reporting month without deleting prior results.

!!! info "Guide scope"
    This guide is based on the project README and the implemented workflow. Screenshots use fictional activities and counts. Workbook links are entered in the app; the guide does not publish team workbook data or authentication state.
