# Deploy on Kubernetes

The same runtime image and persistent-workspace layout can run on Kubernetes. The difference is the image build: Kubernetes does not provide OpenShift's binary Docker BuildConfig and ImageStream path.

## Build an image for your cluster

Replace the example registry and tag with a destination your cluster can pull from. Match the image architecture to the cluster workers.

```bash
npm ci --prefix src/main/webui
./mvnw -B package -DskipTests -Dquarkus.profile=openshift \
  -Dquarkus.kubernetes.deployment-target=kubernetes \
  -Dquarkus.kubernetes.namespace=impact-metrics \
  -Dquarkus.container-image.image=registry.example.internal/team/impact-hours:reviewed-tag

podman build -f src/main/docker/Dockerfile.openshift \
  -t registry.example.internal/team/impact-hours:reviewed-tag .
podman push registry.example.internal/team/impact-hours:reviewed-tag
```

The `openshift` runtime profile supplies the browser and storage settings for both platforms. The selected manifest target is `kubernetes`; it contains the app StatefulSet and Service, without OpenShift build resources.

## Apply the workspace resources

```bash
kubectl create namespace impact-metrics --dry-run=client -o yaml | kubectl apply -f -
kubectl -n impact-metrics apply -f deploy/storage.yaml \
  -f deploy/private-workspace.yaml \
  -f target/kubernetes/kubernetes.yml
kubectl -n impact-metrics rollout status statefulset/impact-hours
```

Configure an image pull secret if required by the registry. The claim uses your cluster's default storage class; select a storage class in `deploy/storage.yaml` if your platform requires one.

The pod runs as a non-root user. On platforms without OpenShift's namespace UID/group assignment, configure a pod UID and volume group accepted by your storage provider and security policy. The image's default user is `1001:0`; the persistent volume must be writable by the pod's runtime identity. Keep the read-only root filesystem and writable `/tmp` and `/dev/shm` volumes.

## Open the app and desktop

```bash
kubectl -n impact-metrics port-forward --address 127.0.0.1 \
  statefulset/impact-hours 8097:8097 6080:6080
```

Use the same app and sign-in desktop URLs as in the [OpenShift guide](openshift.md#3-open-the-app-and-sign-in-desktop). The private NetworkPolicy requires a cluster network plugin that enforces NetworkPolicy. Keep one replica for each workspace.

## Platform boundary

| Shared between platforms | OpenShift-specific |
|---|---|
| Packaged Quarkus app and web UI | Binary Docker build inside the cluster |
| Chromium and private desktop | BuildConfig and ImageStream |
| StatefulSet, PVC and probes | Namespace UID/SCC assignment |
| Runtime state and sessions | `oc` project and rollout commands |

The generator and runtime image have local validation. Confirm registry access, storage permissions and admission policy on your target cluster before putting the workspace into use.
