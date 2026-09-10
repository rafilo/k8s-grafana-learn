# IBM API Connect / DataPower 教学模拟

现已支持可选 [MuleSoft Anypoint 集成层](ANYPOINT.md)。启用后调用链为 DataPower → Anypoint → Java；下文直连架构适用于未启用该选项时。

本目录是 Python 标准库实现的轻量模拟，不包含 IBM 软件，不兼容 IBM 管理 API。
API Connect 模拟产品、Catalog 与 Plan 的只读发布配置；DataPower 模拟执行这些策略。

```text
客户端 ──POST /api/*──> DataPower 模拟网关 ──HTTP──> 现有 Java checkout-api
                            │
                            └──GET /policy──> API Connect 模拟管理服务
Prometheus ──/metrics──> 两个模拟服务 ──> Grafana 看板
```

## 推荐：随现有 Helm 部署

先按项目主 README 启动 Java 应用及监控环境，再执行：

```powershell
$ibmTag = "local-$(Get-Date -Format yyyyMMddHHmmss)"
docker build -t "ibm-api-simulator:$ibmTag" ./ibm-simulator
helm upgrade production-simulator ./charts/production-simulator --namespace monitoring-demo --reuse-values --set ibmSimulator.enabled=true --set-string "ibmSimulator.image=ibm-api-simulator:$ibmTag" --wait --timeout 3m
```

镜像需要在 Kubernetes 节点可用；独立镜像存储需另行导入或推送 registry。
默认启动单副本网关（限流计数在该进程内存中），Prometheus 自动抓取两个服务。
Grafana 自动加载 **IBM API Connect & DataPower Simulation** 看板。

## 替代：Compose 模拟网关 + Kubernetes 后端

```powershell
docker compose --profile ibm up -d --build
```

后端必须已在 `localhost:18080` 运行。不要同时启动 Helm 和 Compose 的模拟服务，端口相同。
Compose 模式不会自动修改 Kubernetes Prometheus 抓取目标；完整监控请用 Helm 模式。

## 验证调用

```powershell
Invoke-RestMethod http://localhost:18082/catalog
$headers = @{ 'X-IBM-Client-Id' = 'demo-client' }
Invoke-WebRequest http://localhost:18081/api/payments -Method Post -Headers $headers -ContentType application/json -Body '{"amount":100}'
# PowerShell 7：无凭证返回 401；超过 10 次/60秒返回 429
Invoke-WebRequest http://localhost:18081/api/payments -Method Post -SkipHttpErrorCheck
1..12 | ForEach-Object { (Invoke-WebRequest http://localhost:18081/api/payments -Method Post -Headers $headers -SkipHttpErrorCheck).StatusCode }
Invoke-RestMethod http://localhost:18081/metrics
```

支持 POST `/api/payments`、`/api/cards/authorize`、`/api/payments/{id}/refund`。
响应包含新生成的 `X-Request-ID` 和 `X-Simulation: true`；成功转发请求携带请求 ID 至后端。
未知 API 返回 404，错误方法返回 405，后端不可达返回 502，超时返回 504。
现有 Java 应用约 3% 的随机 503 会透传。只有实际调用产生请求指标，不自动生成网关流量。

Plan 由 `ibmSimulator.rateLimit` / `rateWindowSeconds` 配置，修改后 Helm upgrade 生效。
这是单客户端、滚动窗口限流；重启清零，无分布式配额。默认客户端 ID 是公开演示值。
每个有效请求同步读取管理端策略，管理端不可用返回 503；真实产品的策略下发/缓存不在模拟范围。
只支持固定后端的 HTTP/JSON 演示，没有 TLS、OAuth、SOAP 转换、Portal、真实发布工作流或 IBM Analytics。
管理端只读，不存储客户凭证。仅用于本地学习环境。

本地验证（Python 3.12+）：`python ibm-simulator/test_integration.py`。
架构依据：[IBM 网关服务职责](https://www.ibm.com/docs/en/api-connect/software/10.0.x_cd?topic=topology-registering-gateway-service)。
