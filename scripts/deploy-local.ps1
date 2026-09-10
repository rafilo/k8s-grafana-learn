[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$ImageTag,
    [Parameter(Mandatory)][string]$GrafanaProjectPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed: $LASTEXITCODE" }
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repo
foreach ($tool in @('docker', 'kubectl', 'helm')) { Get-Command $tool -ErrorAction Stop | Out-Null }
$grafanaProject = (Resolve-Path -LiteralPath $GrafanaProjectPath).Path
if ($grafanaProject -eq $repo) { throw 'Grafana needs a persistent directory outside the runner checkout.' }
if (-not (Test-Path -LiteralPath (Join-Path $grafanaProject '.env'))) { throw 'Persistent Grafana directory requires its existing .env.' }
Invoke-Checked docker @('info', '--format', '{{.ServerVersion}}')
Invoke-Checked kubectl @('--context', 'docker-desktop', 'get', 'nodes')
Invoke-Checked helm @('lint', './charts/production-simulator', '--set', 'ibmSimulator.enabled=true', '--set', 'ibmSimulator.anypointEnabled=true')
Invoke-Checked docker @('build', '-t', "production-simulator:$ImageTag", './demo-app')
Invoke-Checked docker @('build', '-t', "ibm-api-simulator:$ImageTag", './ibm-simulator')
Invoke-Checked helm @('upgrade', '--install', 'production-simulator', './charts/production-simulator',
    '--kube-context', 'docker-desktop', '--namespace', 'monitoring-demo', '--create-namespace',
    '--reset-then-reuse-values', '--set-string', "image.tag=$ImageTag", '--set', 'ibmSimulator.enabled=true',
    '--set', 'ibmSimulator.anypointEnabled=true', '--set-string', "ibmSimulator.image=ibm-api-simulator:$ImageTag",
    '--atomic', '--wait', '--timeout', '5m')
# Docker bind mounts must survive runner workspace cleanup. Keep the existing .env and volumes.
Copy-Item -LiteralPath (Join-Path $repo 'docker-compose.yml') -Destination $grafanaProject -Force
Copy-Item -LiteralPath (Join-Path $repo 'grafana') -Destination $grafanaProject -Recurse -Force
Invoke-Checked docker @('compose', '--project-directory', $grafanaProject, 'up', '-d', 'grafana')
Invoke-Checked docker @('compose', '--project-directory', $grafanaProject, 'restart', 'grafana')
$healthy = $false
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    try {
        foreach ($port in @(18080, 18081, 18082, 18083)) {
            $null = Invoke-RestMethod "http://localhost:$port/health" -TimeoutSec 5
        }
        $null = Invoke-RestMethod 'http://localhost:3001/api/health' -TimeoutSec 5
        $targets = (Invoke-RestMethod 'http://localhost:19090/api/v1/targets' -TimeoutSec 5).data.activeTargets
        $ibm = @($targets | Where-Object { $_.labels.job -eq 'ibm-simulators' })
        if ($ibm.Count -ne 3 -or @($ibm | Where-Object health -ne 'up').Count -gt 0) { throw 'Simulator scrape targets not ready' }
        $healthy = $true
        break
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $healthy) { throw 'Post-deployment health verification failed; inspect diagnostics. No automatic rollback after Helm success.' }
Write-Output "Deployed revision $ImageTag; application, gateways, Grafana and scrape targets healthy."
