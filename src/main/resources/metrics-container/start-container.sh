#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
runtime_dir=${METRICS_RUNTIME_ROOT:-/data/metrics/runtimes}
runtime_leaf=${runtime_dir%/}
if [[ ${runtime_leaf##*/} != runtimes && ${runtime_leaf##*/} != test-runtimes ]]; then
    echo 'METRICS_RUNTIME_ROOT must end in runtimes or test-runtimes.' >&2; exit 1
fi
mkdir -p "$runtime_dir" "$HOME"
# Current instructions follow the deployed application. Per-run frozen copies are preserved.
for name in RUN.md AGENTS.md; do
    install -m 600 "/opt/metrics-instructions/$name" "$runtime_dir/$name"
done

exec java -XX:MaxRAMPercentage=55 -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
    -jar /deployments/quarkus-run.jar
