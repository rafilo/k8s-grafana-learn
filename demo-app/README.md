# Local Kubernetes production simulator

This demo runs two Java `checkout-api` replicas and a small in-cluster Prometheus
instance. The Java application emits Prometheus metrics and structured JSON logs.

## Build and deploy

```powershell
docker build -t production-simulator:local .\demo-app
helm upgrade --install production-simulator .\charts\production-simulator --namespace monitoring-demo --create-namespace --wait
```

## Local access

The chart exposes LoadBalancer Services on localhost ports 18080 and 19090.
If LoadBalancer routing is unavailable, use these fallback commands in separate
terminals while the local ports are free:

```powershell
kubectl port-forward -n monitoring-demo service/production-simulator-production-simulator 18080:18080
kubectl port-forward -n monitoring-demo service/production-simulator-production-simulator-prometheus 19090:19090
```

Then use:

- App health: `http://localhost:18080/health`
- App metrics: `http://localhost:18080/metrics`
- Prometheus: `http://localhost:19090`
- Grafana: `http://localhost:3001`

Grafana accesses Prometheus at `host.docker.internal:19090`. When using the
fallback, keep the second port-forward running while using the dashboard.
For the full startup sequence and rebuilding the Java image with a fresh tag,
see the [root README](../README.md#run-the-project).

## Example metrics and logs

### Banking API metrics

The simulator does not process real cards or payments. `SIMULATE_TRAFFIC=true`
(default) generates synthetic payment, authorization, and refund outcomes each
second. Set it to `false` to measure only incoming HTTP handlers. Actual handler
durations use the monotonic clock through response writing; synthetic durations
are generated observations. Health checks and scrapes are excluded.

| Metric | Meaning |
| --- | --- |
| `http_server_requests_total` | Throughput and status codes, by bounded route, method and source |
| `http_server_request_duration_seconds` | Histogram for p50/p95/p99 and latency SLOs |
| `http_server_requests_in_flight` | Actual executing API handlers |
| `http_response_write_errors_total` | Actual response write failures (created on first failure) |
| `banking_operations_total` | Synthetic payment/authorization/refund approved, declined, error and timeout outcomes |
| `banking_idempotency_replays_total` | Synthetic duplicate-request replay events |
| `dependency_requests_total`, `dependency_timeouts_total` | Synthetic card processor outcomes and timeouts |
| `dependency_request_duration_seconds` | Synthetic dependency latency histogram |
| `network_probe_packets_sent_total`, `network_probe_packets_lost_total` | Synthetic packet probe results; **no real network probes are sent** |
| `network_interface_packets_total`, `network_interface_dropped_packets_total`, `network_interface_errors_total` | Real Linux `/proc/net/dev` counters per interface/direction |
| `network_interface_collection_success` | 1 when Linux interface stats are readable; 0 otherwise (no fabricated zero counters) |

Existing `application_*` metrics remain compatible with the current dashboard and
combine actual and synthetic requests. Existing JVM/CPU/GC metrics remain enabled.
New request metrics use `source="actual"` or `source="simulated"`; synthetic business
and dependency metrics always carry `source="simulated"`. Series appear on first
observation. A declined payment (422) is distinct from a technical failure (5xx).
Histogram buckets range from 5 ms to 10 s plus `+Inf`; quantiles are estimates.

No card numbers, CVV, account/customer/payment identifiers, request bodies or raw
URLs are used in metrics. Refund paths are normalized to `/api/payments/{id}/refund`;
unknown paths and methods become `other` and `OTHER` to bound cardinality.
Prometheus Pod discovery supplies Kubernetes identity; Pod readiness/restarts,
container limits/throttling and node metrics require kube-state-metrics and
kubelet/cAdvisor or node exporters, not fabricated Java measurements.
The Helm chart now includes kube-state-metrics and cAdvisor scraping; see
[Kubernetes monitoring](../charts/production-simulator/MONITORING.md) for the
available real metrics, dashboard, deployment and access scope.

### PromQL examples

These examples select synthetic traffic for demonstration. Change `simulated` to
`actual` for measured HTTP traffic. Aggregate histogram buckets across replicas
before calculating percentiles; never average per-Pod p99 values.

```promql
# p99 in seconds, per service and route (use 0.95 / 0.50 for p95 / p50)
histogram_quantile(0.99, sum by (service, environment, route, le) (rate(http_server_request_duration_seconds_bucket{source="simulated"}[5m])))

# Requests per second
sum by (service, environment) (rate(http_server_requests_total{source="simulated"}[5m]))

# Technical error percentage; returns no result before any 5xx series exists
100 * sum by (service, environment) (rate(http_server_requests_total{source="simulated",status=~"5.."}[5m])) / sum by (service, environment) (rate(http_server_requests_total{source="simulated"}[5m]))

# Percentage completed within 300 ms
100 * sum(rate(http_server_request_duration_seconds_bucket{source="simulated",le="0.3"}[5m])) / sum(rate(http_server_request_duration_seconds_count{source="simulated"}[5m]))

# Synthetic end-to-end packet loss percentage
100 * sum(rate(network_probe_packets_lost_total{source="simulated"}[5m])) / sum(rate(network_probe_packets_sent_total{source="simulated"}[5m]))

# Actual interface drops per second, per Pod scrape target and direction
sum by (instance, direction) (rate(network_interface_dropped_packets_total[5m]))

# Synthetic business decline rate (separate from infrastructure errors)
sum(rate(banking_operations_total{outcome="declined"}[5m])) / sum(rate(banking_operations_total[5m]))
```

Zero traffic can yield NaN for ratios/quantiles; display it as no data. Interface
drops describe the Linux network namespace (normally the Pod), not end-to-end
loss or exclusively this Java process. Real end-to-end loss needs a defined probe
target and packet measurement; HTTP timeouts are not packet loss.

References: [Prometheus histograms](https://prometheus.io/docs/practices/histograms/)
and [Linux interface statistics](https://kernel.org/doc/html/v5.12/networking/statistics.html).

Metrics include request volume/errors/latency, JVM heap and non-heap memory,
threads, garbage collection, process CPU, system CPU, and uptime. Inspect the
generated structured logs with:

```powershell
kubectl logs -n monitoring-demo -l app.kubernetes.io/name=production-simulator --all-containers --prefix=true -f
```
