# Deploy on Kubernetes

The same runtime image and persistent-workspace layout can run on Kubernetes. The difference is the image build: Kubernetes does not provide OpenShift's binary Docker BuildConfig and ImageStream path.

The examples use the `impact-hours` namespace. Substitute your chosen namespace, and keep the original namespace when updating an existing workspace.

## Build an image for your cluster

Replace the example registry and tag with a destination your cluster can pull from. Match the image architecture to the cluster workers.

```bash
npm ci --prefix src/main/webui
./mvnw -B package -DskipTests -Dquarkus.profile=openshift \
  -Dquarkus.kubernetes.deployment-target=kubernetes \
  -Dquarkus.kubernetes.namespace=impact-hours \
  -Dquarkus.container-image.image=registry.example.internal/team/impact-hours:reviewed-tag

podman build -f src/main/docker/Dockerfile.openshift \
  -t registry.example.internal/team/impact-hours:reviewed-tag target/quarkus-app
podman push registry.example.internal/team/impact-hours:reviewed-tag
```

The `openshift` runtime profile supplies the browser and storage settings for both platforms. The selected manifest target is `kubernetes`; it contains the app StatefulSet and Service, without OpenShift build resources.

## Apply the workspace resources

```bash
kubectl create namespace impact-hours --dry-run=client -o yaml | kubectl apply -f -
kubectl -n impact-hours apply -f deploy/storage.yaml \
  -f deploy/workspace-isolation.yaml \
  -f target/kubernetes/kubernetes.yml
kubectl -n impact-hours rollout status statefulset/impact-hours
```

Configure an image pull secret if required by the registry. The claim uses your cluster's default storage class; select a storage class in `deploy/storage.yaml` if your platform requires one.

The pod runs as a non-root user. On platforms without OpenShift's namespace UID/group assignment, configure a pod UID and volume group accepted by your storage provider and security policy. The image's default user is `1001:0`; the persistent volume must be writable by the pod's runtime identity. Keep the read-only root filesystem and writable `/tmp` and `/dev/shm` volumes.

## Access and account sign-in

```bash
kubectl -n impact-hours port-forward --address 127.0.0.1 \
  statefulset/impact-hours 8097:8097
```

Open `http://127.0.0.1:8097`. The app uses the same [local account helper](openshift.md#3-connect-ibm-and-linkedin-from-your-computer); it accepts this loopback URL. The image contains no remote desktop. For tunnel-free access on Kubernetes, provide an authenticated HTTPS gateway and a matching ingress NetworkPolicy before exposing the Service. OpenShift OAuth resources are added only by the OpenShift script. The private NetworkPolicy requires a cluster network plugin that enforces NetworkPolicy. Keep one replica for each workspace.

## Platform boundary

| Shared between platforms | OpenShift-specific |
|---|---|
| Packaged Quarkus app and web UI | Binary Docker build inside the cluster |
| Headless Chromium and local account helper | BuildConfig and ImageStream |
| StatefulSet, PVC and probes | Namespace UID/SCC assignment |
| Runtime state and sessions | `oc` project and rollout commands |

The generator and runtime image have local validation. Confirm registry access, storage permissions and admission policy on your target cluster before putting the workspace into use.
