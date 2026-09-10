# Anypoint 集成模拟

本实现使用 Python，不包含 Mule runtime、DataWeave 引擎或任何 IBM/MuleSoft 产品。

链路：客户端 → DataPower → Anypoint 模拟集成流 → Java 双副本。
API Connect 为 DataPower 提供只读策略；Prometheus 抓取三个模拟服务。

Anypoint 提供 GET `/exchange`（固定资产目录）、`/runtime`（本进程状态）、
`/health`、`/metrics`。三个 POST 业务路径与原 Java API 相同。
空请求体按 `{}` 处理，非 JSON 对象返回 400。
输入 `{"amount":100}` 映射为
`{"operation":"/api/payments","payload":{"amount":100},"source":"anypoint-simulator"}`。
Java 模拟器不处理这些业务字段；集成测试使用回显后端验证实际映射。
响应封装 integration、request_id、backend_status 和 result，同时保留后端状态码。
连接失败返回 502，连接器超时返回 504。不自动重试支付请求。
DataPower 与 Anypoint 日志共享请求 ID；Java 收到该请求头，但现有日志不记录它。

## 部署

```powershell
$tag = "local-$(Get-Date -Format yyyyMMddHHmmss)"
docker build -t "ibm-api-simulator:$tag" ./ibm-simulator
helm upgrade production-simulator ./charts/production-simulator -n monitoring-demo --reset-then-reuse-values --set ibmSimulator.enabled=true --set ibmSimulator.anypointEnabled=true --set-string "ibmSimulator.image=ibm-api-simulator:$tag" --wait --timeout 3m
```

默认 Anypoint 地址 http://localhost:18083/exchange。
综合看板：http://localhost:3001/d/api-integration-simulation。
沿用历史 `ibm_simulator_*` 指标名，`component="mule"` 表示 Anypoint 模拟。
`ibmSimulator.anypointEnabled=false` 可恢复 DataPower 直接转发 Java。
Compose 的 `ibm` profile 现在包含 Anypoint；不能与 Helm 实例同时占用相同端口。

## 验证

```powershell
Invoke-RestMethod http://localhost:18083/exchange
Invoke-RestMethod http://localhost:18081/api/payments -Method Post -Headers @{'X-IBM-Client-Id'='demo-client'} -ContentType application/json -Body '{"amount":100}'
python ibm-simulator/test_integration.py
```

Anypoint 正式平台还包含 API Manager、Exchange、Runtime Manager 等；这里只模拟
只读资产描述及单个 HTTP 集成流。直接访问 18080 或 18083 会绕过网关策略。
所有模拟限流和指标计数均为内存状态。Grafana/Prometheus 持久化卷保持原配置。
