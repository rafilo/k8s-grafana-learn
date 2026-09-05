# Helm + Kubernetes + Grafana Learning Project

This project is a local learning environment for deploying and observing a Kubernetes workload with Helm, Prometheus, and Grafana.

It includes:

- A two-replica Java `checkout-api` simulator deployed to Docker Desktop Kubernetes.
- Prometheus Kubernetes Pod Discovery and five-second scraping of application metrics.
- Simulated request traffic, failures, CPU activity, JVM metrics, and structured JSON logs.
- Grafana running in Docker with a provisioned Prometheus data source and Kubernetes overview dashboard.

## Prerequisites

- Docker Desktop with Kubernetes enabled.
- `kubectl` connected to the Docker Desktop cluster.
- Helm CLI and Docker Compose.

Verify the environment:

```powershell
kubectl config current-context
kubectl get nodes
helm version
```

The expected Kubernetes context is usually `docker-desktop`.

## Run the Project

Run all commands from the project root.

### 1. Start Grafana

Create `.env` from the example if needed, then set a strong local administrator password.

```powershell
Copy-Item .env.example .env
docker compose up -d
```

Grafana is available at [http://localhost:3001](http://localhost:3001). The project uses port `3001` because port `3000` is already occupied by another local environment.

### 2. Build the simulator image

```powershell
docker build -t production-simulator:local .\demo-app
```

The Helm chart uses `imagePullPolicy: IfNotPresent`, so Docker Desktop Kubernetes uses this local image without a container registry.

### 3. Deploy the Helm release

```powershell
helm upgrade --install production-simulator .\charts\production-simulator `
  --namespace monitoring-demo `
  --create-namespace `
  --wait
```

This deploys two application Pods, one Prometheus Pod, persistent `LoadBalancer`
Services, and least-privilege RBAC for Prometheus Pod discovery.

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

All three Pods should be `Running`: two simulator Pods and one Prometheus Pod.

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

The **Local Kubernetes Demo** folder contains the **Production Simulator — Kubernetes Overview** dashboard. It displays request rate, error rate, latency, Pod availability, JVM heap usage, CPU, thread count, garbage collection activity, and a JVM process CPU gauge.

Check the health endpoint:

```powershell
Invoke-WebRequest http://localhost:3001/api/health
```

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
demo-app/                     Java simulator source and Dockerfile
grafana/dashboards/           Provisioned Grafana dashboard JSON
grafana/provisioning/         Provisioned Grafana data source and dashboard provider
docker-compose.yml            Local Grafana service definition
```
