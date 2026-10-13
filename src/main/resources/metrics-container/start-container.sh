#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
runtime_dir=${METRICS_RUNTIME_ROOT:-/data/metrics/runtimes}
runtime_leaf=${runtime_dir%/}
if [[ ${runtime_leaf##*/} != runtimes && ${runtime_leaf##*/} != test-runtimes ]]; then
    echo 'METRICS_RUNTIME_ROOT must end in runtimes or test-runtimes.' >&2; exit 1
fi
mkdir -p "$runtime_dir" "$HOME" /tmp/.X11-unix
# Current instructions follow the deployed application. Per-run frozen copies are preserved.
for name in RUN.md AGENTS.md; do
    install -m 600 "/opt/metrics-instructions/$name" "$runtime_dir/$name"
done

pids=()
cleanup() { if ((${#pids[@]})); then kill -TERM "${pids[@]}" 2>/dev/null || true; fi; }
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

Xvfb "$DISPLAY" -screen 0 1440x1000x24 -nolisten tcp -ac > /tmp/metrics-xvfb.log 2>&1 &
pids+=("$!")
display_ready=false
for attempt in {1..50}; do
    if xdpyinfo -display "$DISPLAY" > /dev/null 2>&1; then display_ready=true; break; fi
    sleep 0.2
done
if [[ $display_ready != true ]]; then echo 'Virtual desktop failed to start.' >&2; exit 1; fi

openbox --sm-disable > /tmp/metrics-openbox.log 2>&1 &
pids+=("$!")
# Both desktop listeners are loopback-only. Access is through an authorized oc port-forward.
x11vnc -display "$DISPLAY" -localhost -rfbport 5900 -forever -shared -nopw \
    > /tmp/metrics-vnc.log 2>&1 &
pids+=("$!")
websockify --web=/usr/share/novnc 127.0.0.1:6080 127.0.0.1:5900 \
    > /tmp/metrics-desktop.log 2>&1 &
pids+=("$!")

java -XX:MaxRAMPercentage=55 -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
    -jar /deployments/quarkus-run.jar &
pids+=("$!")
set +e
wait -n "${pids[@]}"
status=$?
set -e
exit "$status"
