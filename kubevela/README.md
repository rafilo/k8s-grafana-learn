# KubeVela 资源拓扑追踪

日常启动、网页访问和添加应用的详细步骤见 [KubeVela 启动与添加应用](_boot.md)。

本项目使用 KubeVela **1.11.0**，将 `monitoring-demo` 中的 Helm release
`production-simulator` 纳入 `production-simulator-topology` Application。
采用 `read-only` 策略，部署、更新和删除仍由 Helm 负责。无需修改业务 Chart。

## 安装和使用

在项目根目录使用 Windows x64 PowerShell，确保 `kubectl` 可用且当前 context
为 `docker-desktop`。安装控制面需要集群管理员权限；CLI 下载会校验官方 SHA256。
CLI 存放在项目 `.tools/`，无需系统安装 Helm CLI。

```powershell
.\kubevela\topology.ps1 -Action Install
.\kubevela\topology.ps1 -Action Adopt
.\kubevela\topology.ps1 -Action Status
```

脚本在命令失败时立即停止，不会自动切换 context。其他集群需先自行选择 context，
再传入 `-ExpectedContext <context>`。其他 Helm release 可通过 `-Namespace`、
`-Release` 和 `-Application` 指定。Install 会安装或升级控制面到固定版本。

Adopt 从集群中的 Helm release 生成完整 Application，预览保存在
`kubevela/generated/application.yaml`，然后只读纳管并等待 Ready。
该文件可能包含 Helm 配置或 Secret，因此与下载工具一起被 Git 忽略。
不要使用 `--mode take-over` 或 `--recycle`，它们会改变当前 Helm 管理方式。

## 追踪范围

- 本 release 的 Deployment、Service、ConfigMap、ServiceAccount、RBAC、PVC
  等实际存在的资源；关闭的可选组件不会被硬编码加入。
- KubeVela 的图形拓扑根据资源关联规则展开 Deployment → ReplicaSet → Pod 等
  子资源。本地 CLI 的 `--tree` 显示顶层资源；`Status` 额外使用 `--pod`
  显示关联 Pod 与所在节点，状态均为查询时的实际状态。
- Helm 更新资源后可重新查询；如果 Helm 新增或移除了顶层资源，重新运行 Adopt
  刷新 Application 的资源清单。新增 release 也需要单独纳管。
- 这是应用资源关系与健康状态视图，不是全群资源自动发现、历史拓扑数据库或
  HTTP 调用链追踪。CPU、内存和历史指标继续在 Grafana/Prometheus 查看。

## 可选图形界面

```powershell
.\kubevela\topology.ps1 -Action UI
kubectl port-forward -n vela-system service/velaux-server 8080:8000 --address=127.0.0.1
```

保持终端运行，打开 http://localhost:8080。
初始账号见官方 VelaUX 文档，首次登录修改密码。通过应用列表/命名空间找到
`production-simulator-topology` 查看资源拓扑；CLI 树可独立于 VelaUX 使用。
UI action 从官方 addon registry 安装固定版本 VelaUX v1.9.5。

本地验证：KubeVela 控制面与 VelaUX 均 Ready；Application 只读纳管及重复运行
通过，查询得到 20 个顶层资源、4 个 Running Pod（Java 2、Prometheus 1、
kube-state-metrics 1）。纳管未引起业务 Pod 重建。

## 验证与排查

```powershell
kubectl get deployments,pods -n vela-system
kubectl get applications.core.oam.dev production-simulator-topology -n monitoring-demo
kubectl get applications.core.oam.dev production-simulator-topology -n monitoring-demo -o jsonpath='{.spec.policies}'
.\kubevela\topology.ps1 -Action Status
kubectl get pods,svc,pvc -n monitoring-demo
```

策略应包含 `read-only`，Application 应 Ready，业务 Pod 保持正常运行。
若安装失败，检查 `kubectl get events -n vela-system --sort-by=.lastTimestamp`
及 `kubectl logs -n vela-system deployment/kubevela-vela-core --tail=100`。
若纳管失败，确认 release 在指定 namespace 中确实存在，且控制面已就绪。

本地首次安装曾遇到 gateway Pod/EndpointSlice 已 Ready，但 APIService 卡在
MissingEndpoints、控制面等待 gateway 的情况。APIService 的 caBundle 当时仍是
占位值，补入安装器生成的 CA 后立即恢复。如果复现，在另一个终端运行：

```powershell
.\kubevela\topology.ps1 -Action RepairGateway
```

该操作只将 gateway TLS Secret 的 CA 写入对应 APIService，保留 TLS 校验；
不打印或导出 Secret 私钥。原 Install 进程随后继续完成 post-install hooks。

本地集群为 Kubernetes **v1.36.1**，超出官方安装页当前声明的
`>=1.19 && <=1.31` 范围；本地实测不等于该版本组合获得官方支持。

只移除拓扑 Application（保留只读策略时不回收被观察的业务资源）：

```powershell
kubectl delete applications.core.oam.dev production-simulator-topology -n monitoring-demo
```

不要删除 `monitoring-demo` namespace。KubeVela 控制面可以供其他应用共享，
因此不在上述清理命令中卸载。

参考：[安装](https://kubevela.io/docs/installation/kubernetes/)、
[只读纳管](https://kubevela.io/docs/cli/vela_adopt/)、
[拓扑查询](https://kubevela.io/docs/cli/vela_status/)、
[VelaUX](https://kubevela.io/docs/reference/addons/velaux/)。
