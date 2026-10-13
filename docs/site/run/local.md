# Run on your laptop

The local app hosts the web UI and launches the browsers used for SharePoint and LinkedIn sign-in. Start here before moving the workspace to a cluster.

## Prerequisites

- Java **25** and Node.js **22** with npm.
- Git and Chrome or the Playwright browsers.
- Access to the three reporting workbooks and any LinkedIn analytics you collect.
- An optional model API key if you want language-model commentary. The pipeline runs without it.

## Prepare the project

```bash
git clone https://github.com/danieloh30/impact-metrics-appdev.git
cd impact-metrics-appdev
cd src/main/webui
npm ci
npx playwright install chromium chrome
cd ../../..
./mvnw quarkus:dev
```

Open **[http://127.0.0.1:8097](http://127.0.0.1:8097)**. Keep the terminal running. Local development remains bound to loopback.

!!! note "Private repository"
    Authenticate Git with an account that has repository access. The documentation site and the running application are separate: GitHub Pages hosts this guide; Quarkus hosts the metrics UI.

## Connect and choose the month

1. Click **Connect SharePoint** and finish IBM sign-in in the app's browser window.
2. Select the reporting month. For final month-end reporting, use a month that has already closed.
3. Review the source URLs and click **Read workbooks & prepare inputs**.
4. If LinkedIn inputs are present, connect LinkedIn or explicitly continue without it. Missing metrics will remain review items.

Processing starts automatically after preparation and any required LinkedIn choice. There is no contract checkbox or separate start button.

## Optional model commentary

Set these variables before starting dev mode:

```bash
export METRICS_AI_ENABLED=true
export METRICS_MODEL_BASE_URL=https://api.openai.com/v1
export METRICS_MODEL_NAME=gpt-4o-mini
# Set METRICS_MODEL_API_KEY using your approved secret-management method.
./mvnw quarkus:dev
```

Keep keys out of Git. Model request and response logging is disabled. Leave `METRICS_AI_ENABLED=false` for the default deterministic workflow.

## Verify a change

```bash
./mvnw test
cd src/main/webui
npm run build
npm run test:e2e
```

The browser regression suite expects the configured preview URL; see its Playwright configuration and the CI workflow for the preview-server setup. Use the Quarkus Dev MCP test runner when working with the coding agent. Do not run `mvn clean` while dev mode is active.
