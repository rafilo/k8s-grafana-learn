# KubeVela 启动与添加应用

本文所有命令均在项目根目录的 PowerShell 中执行：

```powershell
Set-Location C:\Users\AAA\Desktop\grafana_docker
```

项目使用 KubeVela 1.11.0、VelaUX 1.9.5，默认集群 context 为
`docker-desktop`。控制面位于 `vela-system`，业务位于 `monitoring-demo`。

## 1. 已安装后的日常启动

### 1.1 启动 Kubernetes

打开 Docker Desktop，确保 Kubernetes 已启用，等待集群启动，然后检查：

```powershell
kubectl config current-context
kubectl get nodes
```

context 应为 `docker-desktop`，节点应为 `Ready`。如果当前 context 不对，
确认目标是本地集群后执行：

```powershell
kubectl config use-context docker-desktop
```

### 1.2 等待 KubeVela 和 VelaUX 就绪

KubeVela 和 VelaUX 作为 Kubernetes Deployment 运行，集群恢复后自动启动。
日常打开界面无需重新执行 Install、UI 或 Adopt。

```powershell
kubectl rollout status deployment/kubevela-vela-core -n vela-system --timeout=180s
kubectl rollout status deployment/kubevela-cluster-gateway -n vela-system --timeout=180s
kubectl rollout status deployment/velaux-server -n vela-system --timeout=180s
kubectl get pods -n vela-system
```

### 1.3 打开网页

在一个单独的 PowerShell 终端中运行，并保持该终端打开：

```powershell
kubectl port-forward -n vela-system service/velaux-server 8080:8000 --address=127.0.0.1
```

看到 `Forwarding from 127.0.0.1:8080 -> 8000` 后，打开
[VelaUX 应用列表](http://localhost:8080/applications)。这里只绑定本机地址。
当前安装的服务端口是 **8000**。

首次登录的官方默认账号为 `admin`，密码为 `VelaUX12345`；按提示修改密码。
已经修改过密码时使用自己的密码。登录说明见
[官方 VelaUX 文档](https://kubevela.io/docs/reference/addons/velaux/)。

关闭转发终端或按 Ctrl+C 只会停止本机网页入口，集群内服务继续运行。
电脑重启、Pod 更换或转发断开后，重新运行转发命令。若 8080 已被占用，
已有转发正常时直接使用；否则改为 `8081:8000`，访问
[localhost:8081](http://localhost:8081)。

### 1.4 查看当前项目的拓扑

```powershell
.\kubevela\topology.ps1 -Action Status
```

输出包含顶层资源树、关联 Pod 和所在节点。网页中刷新应用列表，找到
`production-simulator-topology`，进入应用详情并查看资源拓扑/运行资源。
界面语言和应用来源不同，页签名称可能不同。

## 2. 首次安装或集群重建后的初始化

先完成 1.1，再依次执行：

```powershell
# 下载并校验项目内 CLI，安装固定版本控制面。
.\kubevela\topology.ps1 -Action Install

# 安装 VelaUX 插件。
.\kubevela\topology.ps1 -Action UI

# 业务 Helm release 已部署后，将它加入拓扑追踪。
.\kubevela\topology.ps1 -Action Adopt
.\kubevela\topology.ps1 -Action Status
```

然后按 1.3 启动端口转发。Install 需要集群管理权限；已有安装再次执行时会
尝试安装/升级到脚本固定版本。CLI 位于 `.tools/vela/windows-amd64/vela.exe`。
若业务 release 尚未部署，先按[项目 README](../README.md)部署业务，再运行 Adopt。

## 3. 添加已存在的 Helm 应用（本项目采用的方法）

这条路径将现有资源加入 KubeVela 的只读观察视图，后续仍通过 Helm 更新业务。
官方将此方式称为 [read-only adoption](https://kubevela.io/docs/cli/vela_adopt/)。

### 3.1 确定 release 和 namespace

安装了 Helm CLI 时，可以执行：

```powershell
helm list -A
```

记录要观察的 release 名称和 namespace。release 名称与 Deployment 名称
可能不同。本项目 release 是 `production-simulator`，namespace 是
`monitoring-demo`。无需 Helm CLI 也能使用项目的 Adopt 脚本；确认业务资源：

```powershell
kubectl get deployment,service,pvc -n monitoring-demo
```

### 3.2 创建只读 Application

```powershell
.\kubevela\topology.ps1 -Action Adopt `
  -Namespace monitoring-demo `
  -Release production-simulator `
  -Application production-simulator-topology
```

脚本读取实际 release，生成 `kubevela/generated/application.yaml`，随后
提交只读 Application 并等待 Ready。该生成文件被 Git 忽略，可能包含
业务配置，每次运行会覆盖它。请勿随意分享或提交。

添加另一个 Helm 应用时，替换三个参数。例如已有 `orders` release 部署在
`orders-dev` 中时：

```powershell
.\kubevela\topology.ps1 -Action Adopt `
  -Namespace orders-dev `
  -Release orders `
  -Application orders-topology
```

示例不会创建 `orders` release，它必须事先存在。同一 namespace 内为不同
被观察应用使用不同 Application 名称。

### 3.3 验证添加结果

```powershell
kubectl get applications.core.oam.dev production-simulator-topology -n monitoring-demo
kubectl get applications.core.oam.dev production-simulator-topology -n monitoring-demo -o jsonpath='{.spec.policies}'
.\kubevela\topology.ps1 -Action Status `
  -Namespace monitoring-demo `
  -Application production-simulator-topology
```

策略应包含 `read-only`，关联 Deployment 的 Ready 副本数应正常。
刷新 VelaUX 应用列表，清除搜索或项目筛选后进入对应应用。
CLI 创建的 Application 与 VelaUX 自己维护的应用元数据并非完全相同；
即使页面暂未显示，也应先用上述命令确认集群对象和状态。
不要为了显示拓扑再创建一个同名 webservice 部署。

### 3.4 后续维护

修改镜像、副本或业务配置时继续使用原来的 Helm 流程。增加或移除 release
中的顶层资源后，再运行同一条 Adopt 命令刷新清单。Status 是查询时的状态，
不会自动把新 release 加入观察范围。保持 `read-only`，不要加 `--recycle`
或切换 `take-over`；这些选项会改变资源管理方式。

## 4. 添加已经存在、但不是 Helm 部署的资源

使用 CLI 的 native adoption，显式指定资源。下面假设 `demo` namespace 已有
名为 `my-api` 的 Deployment 和 Service；请替换成真实名称：

```powershell
# 确认资源存在。
kubectl get deployment/my-api service/my-api -n demo

# 先查看拟生成的 Application，不提交。
.\.tools\vela\windows-amd64\vela.exe adopt deployment/my-api service/my-api `
  -n demo --app-name my-api-topology --mode read-only

# 确认对象范围后执行纳管。
.\.tools\vela\windows-amd64\vela.exe adopt deployment/my-api service/my-api `
  -n demo --app-name my-api-topology --mode read-only --apply --yes

kubectl wait --for=condition=Ready applications.core.oam.dev/my-api-topology -n demo --timeout=180s
.\.tools\vela\windows-amd64\vela.exe status my-api-topology -n demo --tree --detail
.\.tools\vela\windows-amd64\vela.exe status my-api-topology -n demo --pod
```

需要同时观察 ConfigMap 等资源时，将 `configmap/<名称>` 追加到 adopt 的
资源参数中。CLI 直接使用当前 kubeconfig context，执行前先确认目标集群。

## 5. 在 VelaUX 中创建并部署一个新应用

这条路径会让 KubeVela 创建和管理新工作负载。已有项目的拓扑观察使用第 3 节；
以下适合要新部署一个服务的情况。步骤依据
[官方 UI 应用操作指南](https://kubevela.io/docs/next/how-to/dashboard/application/create-application/)，
按钮文本可能随语言和版本变化，以当前页面提供的选项为准。

### 5.1 准备项目、环境和部署目标

1. 登录 VelaUX，选择可使用的 Project（项目）；如果已有合适的项目即可复用。
2. 在部署目标（Delivery Targets/Targets）管理中，确认目标使用 `local`
   集群。为新服务选择专用 namespace，例如 `vela-demo`。
3. 在环境（Environments）管理中创建或选择 `demo` 环境，将其关联到该部署
   目标，并确认当前项目可以使用这个环境。也可在后续 Bind Environment
   对话框提供的创建入口完成环境设置。
4. 如果所选 namespace 尚不存在，可先在终端执行
   `kubectl create namespace vela-demo`。已有 namespace 无需再次创建。

### 5.2 创建应用配置

1. 打开 **Applications**，点击 **New Application / Create Application**。
2. 填写应用名，例如 `hello-vela`，选择准备好的 Project；应用名使用小写
   字母、数字和连字符。描述可填写“VelaUX 部署演示”。
3. 组件类型选择 **webservice**，组件名使用 `hello-web`。
4. 在镜像字段填写可由集群拉取的镜像，例如 `nginx:stable-alpine`。该标签
   随上游更新变化，正式部署时使用经过验证的固定版本或 digest。
5. 容器端口填写 `80`（TCP）。若表单要求端口名，可填写 `http`。
   配置端口只是声明容器监听端口，是否暴露到集群外还取决于 Service/网关配置。
6. 如表单提供资源和副本配置，为演示设置 1 个副本，CPU request `100m`、
   memory request `64Mi`，limit `500m`、`128Mi`。私有镜像需要先配置
   imagePullSecret；仅填写私有镜像地址不足以授权拉取。
7. 点击 **Submit / Create** 保存。此时是保存应用配置，尚需绑定环境并部署。

### 5.3 绑定环境并部署

1. 打开刚创建的应用详情，点击 **Bind Environment**。
2. 选择第 5.1 节准备的环境并提交；确认目标是 `local` 和 `vela-demo`。
3. 切换到该环境页签，检查待部署组件、镜像及目标，点击 **Deploy**。
4. 等待工作流完成；如果工作流包含人工暂停步骤，检查后按页面提示批准。
5. 工作流成功后打开该环境的资源拓扑，检查 Deployment、ReplicaSet、Pod
   的关系和健康状态。点击 Pod 查看详情/日志（以界面提供的功能为准）。

### 5.4 验证运行与访问

```powershell
kubectl get applications.core.oam.dev -A
kubectl get deployment,replicaset,pod,service -n vela-demo
```

VelaUX 可能根据环境生成实际 Application 名称，以查询结果为准。Pod 应
Running/Ready，Deployment 的可用副本数应符合配置。如果没有外部入口，
可以复制上一步查到的实际 Deployment 名称，通过本机端口访问容器：

```powershell
# 将 <实际Deployment名称> 替换为查询结果，不要原样执行占位符。
kubectl port-forward -n vela-demo deployment/<实际Deployment名称> 18081:80 --address=127.0.0.1
```

保持终端运行，打开 [演示服务](http://localhost:18081)。后续在 VelaUX
修改组件配置后，需要再次 Deploy 才会更新环境中的实例。

## 6. 常见问题

| 现象 | 处理方法 |
| --- | --- |
| Kubernetes API 连接失败 | 启动 Docker Desktop Kubernetes，确认 context 和节点 Ready。 |
| `velaux-server` 不存在 | 运行 `topology.ps1 -Action UI`，然后等待部署就绪。 |
| 网页打不开/连接断开 | 检查 port-forward 终端，重新运行；确认端口是 `8080:8000`。 |
| 首次安装控制面一直等待 gateway | 检查下方已知问题，符合条件时执行 RepairGateway。 |
| Adopt 找不到 release | 检查 release 名与 namespace，先部署原 Helm 应用。 |
| CLI 有应用但 UI 看不到 | 刷新并清除筛选，检查登录用户的项目权限；检查 `kubectl logs -n vela-system deployment/velaux-server --tail=100` 的同步错误。 |
| 新建应用 Pod 为 ImagePullBackOff | 检查镜像名、标签、仓库网络和拉取凭据。 |
| Helm 新增资源没有进入拓扑 | 对原 release 再运行 Adopt，刷新顶层资源清单。 |

本地安装曾出现 gateway Pod/EndpointSlice Ready，但 APIService 保留占位
caBundle 并报告 MissingEndpoints 的启动阻塞。只有复现该情况时，在另一终端执行：

```powershell
.\kubevela\topology.ps1 -Action RepairGateway
```

它将安装器生成的 gateway CA 写入对应 APIService，保留 TLS 校验，随后原
Install 进程继续。更多范围、清理说明以及 Kubernetes 版本兼容性限制见
[资源拓扑配置说明](README.md)。
