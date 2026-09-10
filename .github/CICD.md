# GitHub Actions CI/CD

## CI

PR、main 推送及手动触发运行 `.github/workflows/ci.yml`，使用 GitHub 托管 Ubuntu Runner：
Python 3.12 HTTP 集成测试、Grafana JSON/UID 检查、三种 Helm 配置 lint/render、
Compose 校验，以及 Java 与模拟器 Docker 构建。CI 不访问本地集群，不推送镜像。

建议在 main 的 branch protection/ruleset 中要求 `validate` 检查通过。
Dependabot 每周检查 Actions 版本更新。

## 本地 CD

GitHub 云端不能直接连接 Docker Desktop。`Deploy local Kubernetes` 仅允许 main 手动触发，
先对同一提交运行 CI，再在 Windows 自托管 Runner 上执行部署。部署串行运行，不中断正在执行的升级。

首次配置：

1. 将工作流、脚本和所有关联项目文件提交并推送到 main（尤其 `ibm-simulator/`、Helm 模板及看板）。
2. 在仓库 Settings → Environments 创建 `local-kubernetes`，限制部署分支为 main；
   可配置 required reviewers。
3. 在该 Environment 添加变量 `GRAFANA_PROJECT_PATH`，值为已有持久项目目录，
   例如 `C:\Users\AAA\Desktop\grafana_docker`。该目录必须有正确的 `.env`，且不能是 Runner checkout。
4. 在 Settings → Actions → Runners 按 GitHub 提供的命令注册专用 Windows x64 Runner，
   添加标签 `grafana-local`。使用能访问 Docker Desktop 和 kubeconfig 的账户运行。
   安装 PowerShell 7、Docker Compose、kubectl 和 Helm 3.21.4（脚本使用 Helm 3 的 `--atomic`）。
5. 确认 `docker-desktop` context 正常，节点可访问本地构建镜像；独立镜像存储需要先配置镜像导入或仓库。
6. 在 Actions → Deploy local Kubernetes → Run workflow，选择 main。

不要让不可信 PR 工作流使用此 Runner；它能访问 Docker 与集群。
公开仓库更适合隔离的临时 Runner，配置 Runner 访问限制并审查 workflow 修改。
参考：https://docs.github.com/en/actions/reference/security/secure-use

## 部署行为与失败处理

- 从选定提交构建镜像，以完整 commit SHA 标记，不使用 `latest`。
- 固定操作 `docker-desktop`、`monitoring-demo` 和 `production-simulator` release。
- 保留已有 Helm 用户值，更新 chart 默认配置，开启 API Connect、DataPower、Anypoint 模拟。
- Helm 升级失败通过 `--atomic` 回滚；首次安装失败会清理该次 release。
- 更新持久目录的 Compose 与 Grafana 文件并重启 Grafana，保留 `.env` 与数据卷。
  此步骤不删除已移除的旧看板文件；删除/重命名看板时需要清理持久目录中对应旧文件。
- 检查四个应用健康端点、Grafana 和三个 Prometheus scrape target。
- 后置检查失败会让工作流失败并输出 Pod/Service/Event；不会声称全部系统已原子回滚。
  Helm、Grafana 文件和 Docker 容器不属于同一个事务。

此流程在本地重建同一源码提交，不是将 CI 构建产物原样晋级。未来采用远程集群时，
可扩展为 CI 推送 registry、CD 按 digest 部署。

当前仅增加仓库配置，尚未在 GitHub 注册 Runner、配置 Environment 或触发远端工作流。
