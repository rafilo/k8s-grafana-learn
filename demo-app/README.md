# Local Kubernetes production simulator

This demo runs two Java `checkout-api` replicas and a small in-cluster Prometheus
instance. The Java application emits Prometheus metrics and structured JSON logs.

## Build and deploy

```powershell
docker build -t production-simulator:local .\demo-app
helm upgrade --install production-simulator .\charts\production-simulator --namespace monitoring-demo --create-namespace --wait
```

## Local access

Docker Desktop Kubernetes does not expose NodePort services to Windows localhost
in this setup. Keep these commands running in separate terminals:

```powershell
kubectl port-forward -n monitoring-demo service/production-simulator-production-simulator 18080:8080
kubectl port-forward -n monitoring-demo service/production-simulator-production-simulator-prometheus 19090:9090
```

Then use:

- App health: `http://localhost:18080/health`
- App metrics: `http://localhost:18080/metrics`
- Prometheus: `http://localhost:19090`
- Grafana: `http://localhost:3001`

Grafana uses the Prometheus port-forward at `host.docker.internal:19090`; keep
the second port-forward running while using the dashboard.

## Example metrics and logs

Metrics include request volume/errors/latency, JVM heap and non-heap memory,
threads, garbage collection, process CPU, system CPU, and uptime. Inspect the
generated structured logs with:

```powershell
kubectl logs -n monitoring-demo -l app.kubernetes.io/name=production-simulator --all-containers --prefix=true -f
```
