# Helm + Kubernetes + Grafana Learning Project

This project is a local learning environment for deploying and observing a Kubernetes workload with Helm, Prometheus, and Grafana.

It includes:

- A two-replica Java `checkout-api` simulator deployed to Docker Desktop Kubernetes.
- Prometheus Kubernetes Pod Discovery and five-second scraping of application metrics.
- Simulated request traffic, failures, CPU activity, JVM metrics, and structured JSON logs.
- Grafana running in Docker with a provisioned Prometheus data source and Kubernetes overview dashboard.
- Kubernetes state and container metrics with a dedicated 20-panel dashboard; see [monitoring setup](charts/production-simulator/MONITORING.md).

## Prerequisites

- Docker Desktop with Kubernetes enabled.
- `kubectl` connected to the Docker Desktop cluster.
- Helm CLI 3.14+ and Docker Compose (upgrade examples use `--reset-then-reuse-values`).

Verify the environment:

```powershell
docker info
kubectl config current-context
kubectl get nodes
helm version
```

The expected Kubernetes context is usually `docker-desktop`.
Start Docker Desktop and enable its Kubernetes cluster before continuing. Docker
being available does not mean the Kubernetes API is ready: `kubectl get nodes`
must succeed and the nodes should be `Ready`. If necessary, select the local
context with `kubectl config use-context docker-desktop`.

## Run the Project

Run all commands from the project root.

### 1. Start Grafana

Create `.env` from the example if needed, then set a strong local administrator password.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# Set GRAFANA_ADMIN_PASSWORD in .env before starting Grafana.
docker compose up -d
```

Grafana is available at [http://localhost:3001](http://localhost:3001). The project uses port `3001` because port `3000` is already occupied by another local environment.

### 2. Build the simulator image

```powershell
$simulatorTag = "metrics-$(Get-Date -Format yyyyMMddHHmmss)"
docker build -t "production-simulator:$simulatorTag" .\demo-app
```

Keep the same PowerShell session for the next step. A new image tag ensures an
existing Java Deployment rolls out the updated metric code; rebuilding the same
`:local` tag alone does not restart Pods. The image must be available to the
Kubernetes nodes. If your Docker Desktop cluster uses a separate image store,
load the image into that cluster or push it to a node-accessible registry and
set `image.repository` accordingly. `IfNotPresent` does not transfer local images.

### 3. Deploy the Helm release

```powershell
helm lint .\charts\production-simulator
helm upgrade --install production-simulator .\charts\production-simulator `
  --namespace monitoring-demo `
  --create-namespace `
  --set-string "image.tag=$simulatorTag" `
  --set kubernetesMetrics.enabled=true `
  --set kubernetesMetrics.cadvisorEnabled=true `
  --wait --timeout 3m
kubectl rollout status deployment/production-simulator-production-simulator --namespace monitoring-demo --timeout=180s
```

This starts both monitoring pipelines: two Java Pods, Prometheus, and
kube-state-metrics, plus `LoadBalancer` Services and collector RBAC. Prometheus
scrapes Java every **5 seconds** and Kubernetes collectors every **15 seconds**.
No separate cAdvisor Pod is needed; it is exposed by kubelet. The enabled
Kubernetes collectors require cluster-level RBAC creation privileges; see the
access scope below.

### 4. Access persistent Kubernetes Services

The Helm chart publishes both services as Kubernetes `LoadBalancer` Services:

- Application: `http://localhost:18080`
- Prometheus: `http://localhost:19090`

Grafana accesses Prometheus through `host.docker.internal:19090`. Unlike
`kubectl port-forward`, these Service endpoints are managed by Kubernetes and do
not depend on a terminal process remaining open.

### 5. Apply configuration changes

After editing a Helm template or `values.yaml`, apply the revised desired state:

```powershell
helm upgrade production-simulator .\charts\production-simulator `
  --namespace monitoring-demo `
  --wait `
  --timeout 3m
```

The Prometheus Deployment includes a checksum of its ConfigMap in its Pod
template. A change to `prometheus-configmap.yaml` therefore triggers a rolling
restart automatically, so the new Prometheus Pod starts with the revised scrape
configuration.

When updating **Java source**, repeat build/deploy steps 2–3 with a new image tag.
When updating only Grafana dashboard JSON, the provisioned file is picked up by
Grafana automatically. Helm upgrades do not build Java images.

## Java API metric monitoring

The Java process exposes Prometheus text at `/metrics`. Default background traffic
generates payment, card authorization and refund observations every second, so
the demo produces metrics without sending requests. It does not process real
payments or contact a card processor.

| Metrics | What to monitor |
| --- | --- |
| `http_server_requests_total` | Request rate and HTTP status codes, grouped by route/method/source |
| `http_server_request_duration_seconds_bucket`, `_sum`, `_count` | Latency histogram for p50/p95/p99 and latency SLOs; seconds |
| `http_server_requests_in_flight`, `http_response_write_errors_total` | Actual executing handlers and response write failures |
| `banking_operations_total` | Simulated payment/authorization/refund results: approved, declined, error, timeout |
| `banking_idempotency_replays_total` | Simulated duplicate-request replays |
| `dependency_requests_total`, `dependency_timeouts_total`, `dependency_request_duration_seconds_*` | Simulated card processor errors, timeouts and latency |
| `network_probe_packets_sent_total`, `network_probe_packets_lost_total` | Simulated packet-loss observations; no probe packets are actually sent |
| `network_interface_packets_total`, `network_interface_dropped_packets_total`, `network_interface_errors_total` | Actual Linux network namespace counters, by interface/direction |
| `network_interface_collection_success` | Whether `/proc/net/dev` can be read; 0 means unavailable |
| Existing `application_*`, `jvm_*`, `process_cpu_usage_ratio`, `system_cpu_usage_ratio` | Backward-compatible totals, JVM memory/threads/GC/uptime and CPU |

New HTTP series distinguish `source="simulated"` from `source="actual"`. Actual
means an incoming request was measured through response writing; this is still a
test handler with random 503 responses. Business/dependency/probe results are
always simulated. Existing `application_*` totals combine both sources. Health
and metrics handlers are excluded from API request counts.

Labels contain bounded routes such as `/api/payments/{id}/refund`, not card numbers,
CVV, payment IDs or request bodies. Kubernetes discovery adds `namespace`, `pod`,
`node` and `container` to scraped Java series.

### Generate actual HTTP observations

In PowerShell 7, send test requests without real financial data:

```powershell
1..20 | ForEach-Object {
  Invoke-WebRequest -Method Post -Uri http://localhost:18080/api/payments -SkipHttpErrorCheck | Select-Object StatusCode
}
(Invoke-WebRequest http://localhost:18080/metrics).Content
```

503 responses are expected occasionally. Each request to the Service may reach a
different Pod; Prometheus scrapes both Pods and provides the combined view.
To temporarily stop generated traffic while testing actual requests:

```powershell
kubectl set env deployment/production-simulator-production-simulator --namespace monitoring-demo SIMULATE_TRAFFIC=false
kubectl rollout status deployment/production-simulator-production-simulator --namespace monitoring-demo --timeout=180s
```

Restore the default with the same command using `SIMULATE_TRAFFIC=true`. This is
a live Deployment override, not a chart value; check it after Helm upgrades.
Disabling generated traffic does not disable the handler's random failure behavior.
Historical simulated series remain visible until the selected query window ages out.

### Query the new Java metrics

Use Prometheus, or Grafana **Explore** with the provisioned Prometheus datasource.
The existing overview dashboard still shows average latency and legacy totals;
paste these queries into Explore for the new p99/business/network metrics.
Run each query separately. Replace `simulated` with `actual` for measured requests.

```promql
# p99 latency in seconds, across Pods, per route (0.95 / 0.50 for p95 / p50)
histogram_quantile(0.99, sum by (service, environment, route, le) (rate(http_server_request_duration_seconds_bucket{source="simulated"}[5m])))

# Requests per second
sum(rate(http_server_requests_total{source="simulated"}[5m]))

# Technical HTTP error percentage (5xx); a business decline is 422
100 * sum(rate(http_server_requests_total{source="simulated",status=~"5.."}[5m])) / sum(rate(http_server_requests_total{source="simulated"}[5m]))

# Business results per second
sum by (operation, outcome) (rate(banking_operations_total{source="simulated"}[5m]))

# Dependency p99 in seconds
histogram_quantile(0.99, sum by (dependency, le) (rate(dependency_request_duration_seconds_bucket{source="simulated"}[5m])))

# SIMULATED end-to-end packet-loss percentage
100 * sum(rate(network_probe_packets_lost_total{source="simulated"}[5m])) / sum(rate(network_probe_packets_sent_total{source="simulated"}[5m]))

# Actual interface drops per second
sum by (pod, direction) (rate(network_interface_dropped_packets_total[5m]))
```

Histograms provide approximate quantiles; aggregate buckets before calculating
p99 rather than averaging Pod percentiles. Series appear on first observation;
error queries may have no result until an error occurs. Rates need at least two
scrapes; allow several minutes for a useful five-minute window. Zero traffic may
produce NaN. Interface drops and HTTP errors are not end-to-end packet loss.
See [Java metric details](demo-app/README.md) for more queries and semantics.

## Kubernetes metric monitoring

Steps 1–3 enable this by default. The configuration in
`charts/production-simulator/values.yaml` is:

```yaml
kubernetesMetrics:
  enabled: true
  kubeStateMetricsImage: registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.17.0
  scrapeInterval: 15s
  cadvisorEnabled: true
```

For an existing release whose Java image is already updated, enable just the
collectors without rebuilding Java:

```powershell
helm upgrade production-simulator .\charts\production-simulator `
  --namespace monitoring-demo `
  --set kubernetesMetrics.enabled=true `
  --set kubernetesMetrics.cadvisorEnabled=true `
  --reset-then-reuse-values --wait --timeout 3m
```

| Source | Metrics and scope |
| --- | --- |
| kube-state-metrics | Namespace Pod Ready/phase, restart counts, waiting reasons, last OOMKilled reason |
| kube-state-metrics | Container requests/limits; Deployment/StatefulSet/DaemonSet replicas; HPA replicas; PVC phases |
| kube-state-metrics | Cluster-wide node Ready, memory/disk/PID pressure and allocatable CPU/memory |
| kubelet/cAdvisor | Namespace container CPU/working-set memory, CPU throttling, Pod network bytes/drops, filesystem metrics where supported |
| Prometheus `up` | Collector reachability; separate from Kubernetes Pod Ready |

Prometheus accesses cAdvisor through the API server using the service account
token and verified cluster CA. `get nodes/proxy` is broader than metrics-only
access and cannot be restricted to one URL by Kubernetes RBAC. Workload state
access is namespace-scoped; node discovery is cluster-scoped. See
[collector permissions and coverage](charts/production-simulator/MONITORING.md).

To retain kube-state-metrics but disable cAdvisor and its proxy RBAC, run:

```powershell
helm upgrade production-simulator .\charts\production-simulator --namespace monitoring-demo --reset-then-reuse-values --set kubernetesMetrics.cadvisorEnabled=false --wait
```

Use `--set kubernetesMetrics.enabled=false` instead to disable both Kubernetes
collectors. Java scraping continues in either case.

### Verify collectors and open the dashboard

Open [Prometheus Targets](http://localhost:19090/targets). Expect two
`production-simulator` targets, one `kube-state-metrics` target and one
`kubernetes-cadvisor` target per node, all `UP`. Then confirm metric presence:

```promql
kube_pod_status_ready{namespace="monitoring-demo",condition="true"}
increase(kube_pod_container_status_restarts_total{namespace="monitoring-demo"}[15m])
sum by (pod, container) (rate(container_cpu_usage_seconds_total{job="kubernetes-cadvisor",namespace="monitoring-demo",container!="",container!="POD"}[5m]))
container_memory_working_set_bytes{job="kubernetes-cadvisor",namespace="monitoring-demo",container="app"}
kube_node_status_condition{condition="Ready",status="true"}
```

In Grafana, open **Local Kubernetes Demo → Kubernetes Runtime Metrics** and select
`monitoring-demo` in **Namespace**. Its 20 panels include resource usage and limits,
CPU throttling, restarts/OOM, network drops, replicas and node health. Node panels
remain cluster-wide regardless of namespace selection. HPA/PVC/StatefulSet/
DaemonSet panels can have no data when those resources do not exist; this demo
does not create them. An absent cAdvisor metric may indicate runtime support is
missing. Node allocatable capacity is not actual host utilization, and last
OOMKilled is a retained reason, not a new OOM event counter.

## Check Project Status

### Helm status

```powershell
helm list --namespace monitoring-demo
helm status production-simulator --namespace monitoring-demo
```

### Kubernetes status

```powershell
kubectl get pods,services --namespace monitoring-demo
kubectl get deployment --namespace monitoring-demo
kubectl get events --namespace monitoring-demo --sort-by=.lastTimestamp
```

With Kubernetes metrics enabled (default), all four Pods should be `Running`: two simulator Pods, one Prometheus Pod, and one kube-state-metrics Pod.

### Application health, metrics, and logs

Use the persistent application Service endpoint:

```powershell
Invoke-WebRequest http://localhost:18080/health
Invoke-WebRequest http://localhost:18080/metrics
```

Follow structured JSON logs:

```powershell
kubectl logs --namespace monitoring-demo `
  -l app.kubernetes.io/name=production-simulator `
  --all-containers --prefix=true --follow
```

### Prometheus status

Open [http://localhost:19090](http://localhost:19090), then query:

```promql
up{job="production-simulator"}
```

The query should return two healthy targets. Other useful queries:

```promql
sum(rate(application_requests_total[1m]))
```

```promql
100 * sum(rate(application_errors_total[5m])) / clamp_min(sum(rate(application_requests_total[5m])), 0.001)
```

### Grafana status

Open [http://localhost:3001](http://localhost:3001) and sign in with the local administrator credentials stored in `.env`.

The **Local Kubernetes Demo** folder contains **Production Simulator — Kubernetes
Overview** (legacy request rate/errors/average latency, application scrape targets,
JVM memory/CPU/threads/GC) and **Kubernetes Runtime Metrics** (20 Kubernetes panels).
The overview's target count measures scrape reachability, not Pod readiness.

Check the health endpoint:

```powershell
Invoke-WebRequest http://localhost:3001/api/health
```

## Troubleshooting monitoring startup

| Symptom | Check / action |
| --- | --- |
| `kubectl` reports connection refused | Start Docker Desktop Kubernetes, confirm context and wait for `kubectl get nodes` to succeed. Docker Engine alone is insufficient. |
| Java Pod has `ImagePullBackOff` / `ErrImageNeverPull` | Ensure the exact image tag exists in the node image store or an accessible registry. Rebuilding a host image alone may not make it available to nodes. |
| New Java metrics are missing | Confirm the Deployment uses the newly built tag and rollout completed. Generate traffic; some series are created only after an event. |
| kube-state-metrics is `UP`, but `kube_*` series are absent | Inspect exporter logs for list/watch permission errors. Reachable HTTP does not prove collectors are working. |
| cAdvisor target is `DOWN` | Inspect the target error for API proxy RBAC, TLS or kubelet errors. Do not disable certificate validation to hide the cause. |
| Grafana has no data | Check datasource access to `host.docker.internal:19090`, Prometheus Targets, the namespace selector and time range. Allow at least two scrapes for rates. |
| Local Service URL does not respond | Check `kubectl get svc -n monitoring-demo`; if LoadBalancer routing is unavailable, use the fallback below. |

Useful diagnostics:

```powershell
kubectl get pods,svc -n monitoring-demo
kubectl get deployment production-simulator-production-simulator -n monitoring-demo -o jsonpath='{.spec.template.spec.containers[0].image}'
kubectl logs -n monitoring-demo deployment/production-simulator-production-simulator-kube-state-metrics --tail=100
kubectl logs -n monitoring-demo deployment/production-simulator-production-simulator-prometheus --tail=100
```

If the LoadBalancer endpoints are unavailable and the local ports are free, run
each fallback in a separate terminal and leave both running:

```powershell
kubectl port-forward -n monitoring-demo service/production-simulator-production-simulator 18080:18080
kubectl port-forward -n monitoring-demo service/production-simulator-production-simulator-prometheus 19090:19090
```

The right-hand ports are **Service ports** (18080/19090), not container ports
(8080/9090). Keep using the same application, Prometheus and Grafana URLs.

## Cleanup

Remove the Helm release:

```powershell
helm uninstall production-simulator --namespace monitoring-demo
```

Remove the empty namespace:

```powershell
kubectl delete namespace monitoring-demo
```

Stop Grafana while retaining its dashboards and database volume:

```powershell
docker compose down
```

To also delete Grafana's persisted local data, use this destructive command:

```powershell
docker compose down --volumes
```

Optionally remove the local simulator image after uninstalling the Helm release:

```powershell
docker image rm production-simulator:local
```

## Repository Layout

```text
charts/production-simulator/
  templates/app-deployment.yaml          Application Deployment
  templates/app-service.yaml             Application LoadBalancer Service
  templates/prometheus-configmap.yaml    Prometheus scrape configuration
  templates/prometheus-serviceaccount.yaml
  templates/prometheus-role.yaml
  templates/prometheus-rolebinding.yaml  Pod Discovery identity and RBAC
  templates/prometheus-deployment.yaml   Prometheus Deployment and config checksum
  templates/prometheus-service.yaml      Prometheus LoadBalancer Service
  templates/kubernetes-metrics.yaml      Kubernetes collectors and RBAC
  MONITORING.md                         Collector coverage and permissions
demo-app/                     Java simulator source and Dockerfile
grafana/dashboards/           Provisioned Grafana dashboard JSON
  kubernetes-runtime-metrics.json        Kubernetes Runtime Metrics (20 panels)
grafana/provisioning/         Provisioned Grafana data source and dashboard provider
docker-compose.yml            Local Grafana service definition
```
