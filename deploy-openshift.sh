#!/usr/bin/env bash
set -Eeuo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$repo_dir"
mode=generate
namespace=${METRICS_NAMESPACE:-impact-metrics}
image_tag=${METRICS_IMAGE_TAG:-}
usage() {
    cat <<'HELP'
Usage: ./deploy-openshift.sh [--generate|--deploy|--connect|--status] [--namespace NAME] [--image-tag TAG]
  --generate  Package and validate OpenShift + Kubernetes manifests; no cluster changes (default).
  --deploy    Build the image inside an authenticated OpenShift project, then deploy one workspace.
  --connect   Open a loopback-only tunnel for the app (8097) and sign-in desktop (6080).
  --status    Inspect the app StatefulSet, pods, storage and build status.
Requires Java 25 + Node.js 22/npm for packaging; oc login for cluster actions.
HELP
}
while (($#)); do
    case "$1" in
        --generate) mode=generate; shift;;
        --deploy) mode=deploy; shift;;
        --connect) mode=connect; shift;;
        --status) mode=status; shift;;
        --namespace) namespace=${2:?--namespace requires a value}; shift 2;;
        --image-tag) image_tag=${2:?--image-tag requires a value}; shift 2;;
        --help|-h) usage; exit 0;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2;;
    esac
done
if [[ ! $namespace =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ || ${#namespace} -gt 63 ]]; then
    echo 'Namespace must be a DNS-compatible name of at most 63 characters.' >&2; exit 2
fi
if [[ -z $image_tag ]]; then image_tag=$(git rev-parse --short=12 HEAD); fi
if [[ ! $image_tag =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]*$ || ${#image_tag} -gt 128 ]]; then
    echo 'Image tag is not a valid container tag.' >&2; exit 2
fi
if [[ $mode != generate ]]; then
    command -v oc >/dev/null || { echo 'Install oc and sign in to OpenShift first.' >&2; exit 1; }
    oc whoami >/dev/null
fi
if [[ $mode == connect ]]; then
    printf 'App: http://127.0.0.1:8097\nSign-in desktop: http://127.0.0.1:6080/vnc.html?autoconnect=1&resize=scale\nKeep this terminal open. Both listeners are forwarded only to loopback.\n'
    exec oc -n "$namespace" port-forward --address 127.0.0.1 statefulset/impact-hours 8097:8097 6080:6080
fi
if [[ $mode == status ]]; then
    exec oc -n "$namespace" get statefulset,pods,pvc,builds -l app.kubernetes.io/name=impact-hours
fi
# Match dev.sh's macOS/SDKMAN lookup before Maven or any cluster mutation.
if [[ -z ${JAVA_HOME:-} ]]; then
    for metrics_jdk in "$HOME/.sdkman/candidates/java/current" /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
        if [[ -x $metrics_jdk/bin/java ]]; then export JAVA_HOME="$metrics_jdk"; break; fi
    done
fi
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! metrics_java_version=$(java -version 2>&1); then
    echo 'A working Java 25+ JDK is required. Set JAVA_HOME to your JDK installation.' >&2; exit 1
fi
metrics_java_major=$(printf '%s\n' "$metrics_java_version" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
if [[ ! $metrics_java_major =~ ^[0-9]+$ || $metrics_java_major -lt 25 ]]; then
    echo 'Java 25+ is required. Select a supported JDK using JAVA_HOME or SDKMAN.' >&2; exit 1
fi
command -v npm >/dev/null || { echo 'Node.js 22 and npm are required.' >&2; exit 1; }
npm ci --prefix src/main/webui

args=(-B package -DskipTests -Dquarkus.profile=openshift
    "-Dquarkus.openshift.namespace=$namespace" "-Dquarkus.kubernetes.namespace=$namespace"
    "-Dquarkus.container-image.group=$namespace" "-Dquarkus.container-image.tag=$image_tag")
./mvnw "${args[@]}"
node scripts/check-deployment.mjs
if [[ $mode == deploy ]]; then
    if ! oc get project "$namespace" >/dev/null 2>&1; then oc new-project "$namespace"; fi
    oc -n "$namespace" apply -f deploy/storage.yaml -f deploy/private-workspace.yaml
    # Generate and validate first; the extension performs the in-cluster build and deployment.
    ./mvnw "${args[@]}" -Dquarkus.openshift.deploy=true
    oc -n "$namespace" rollout status statefulset/impact-hours --timeout=300s
    for metrics_endpoint in runs chart-history; do
        oc -n "$namespace" exec statefulset/impact-hours -- curl --fail --silent --show-error "http://127.0.0.1:8097/api/$metrics_endpoint" > /dev/null
    done
    printf 'Stateful API checks passed: run history and chart history.\n'
    printf 'Workspace deployed. Connect with: ./deploy-openshift.sh --connect --namespace %s\n' "$namespace"
else
    printf 'Validated manifests: target/kubernetes/openshift.yml and target/kubernetes/kubernetes.yml\n'
    printf 'This command made no cluster changes. Deploy with: ./deploy-openshift.sh --deploy --namespace %s\n' "$namespace"
fi
