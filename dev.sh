#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for metrics_jdk in /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$metrics_jdk/bin/java" ]]; then export JAVA_HOME="$metrics_jdk";break;fi
  done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH";fi
exec ./mvnw quarkus:dev "$@"
