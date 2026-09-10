[CmdletBinding()]
param(
    [ValidateSet('Install', 'RepairGateway', 'Adopt', 'Status', 'UI')]
    [string]$Action = 'Status',
    [string]$ExpectedContext = 'docker-desktop',
    [string]$Namespace = 'monitoring-demo',
    [string]$Release = 'production-simulator',
    [string]$Application = 'production-simulator-topology'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$version = '1.11.0'
$toolDir = Join-Path $PSScriptRoot '../.tools/vela'
$vela = Join-Path $toolDir 'windows-amd64/vela.exe'

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed (exit $LASTEXITCODE)." }
}

$context = Invoke-Checked kubectl @('config', 'current-context')
if ($context.Trim() -ne $ExpectedContext) {
    throw "Expected context '$ExpectedContext', found '$context'. Select the intended context explicitly."
}
Invoke-Checked kubectl @('get', 'nodes', '--request-timeout=15s')

if ($Action -eq 'RepairGateway') {
    # The chart's post-install CA patch may be blocked by its readiness wait.
    $secretJson = Invoke-Checked kubectl @('get', 'secret', 'kubevela-vela-core-cluster-gateway-tls-v2', '-n', 'vela-system', '-o', 'json')
    $gatewaySecret = $secretJson | ConvertFrom-Json
    if (-not $gatewaySecret.data.ca) { throw 'Gateway CA is missing.' }
    $patch = @{spec = @{caBundle = $gatewaySecret.data.ca}} | ConvertTo-Json -Compress
    Invoke-Checked kubectl @('patch', 'apiservice', 'v1alpha1.cluster.core.oam.dev', '--type=merge', '-p', $patch)
    Invoke-Checked kubectl @('wait', '--for=condition=Available', 'apiservice/v1alpha1.cluster.core.oam.dev', '--timeout=60s')
    return
}

if ($Action -eq 'Install') {
    if (-not (Test-Path $vela)) {
        New-Item -ItemType Directory -Force $toolDir | Out-Null
        $archive = "vela-v$version-windows-amd64.zip"
        $base = "https://github.com/kubevela/kubevela/releases/download/v$version"
        $zip = Join-Path $toolDir 'vela.zip'
        $checksums = Join-Path $toolDir 'sha256sums.txt'
        Invoke-WebRequest "$base/$archive" -OutFile $zip
        Invoke-WebRequest "$base/sha256sums.txt" -OutFile $checksums
        $line = @(Get-Content $checksums | Where-Object { $_ -match "\s+$([regex]::Escape($archive))$" })
        if ($line.Count -ne 1) { throw 'Release checksum entry missing or ambiguous.' }
        $expectedHash = ($line[0] -split '\s+')[0]
        if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne $expectedHash) { throw 'CLI checksum mismatch.' }
        Expand-Archive $zip -DestinationPath $toolDir -Force
    }
    Invoke-Checked $vela @('install', '--version', $version, '--namespace', 'vela-system', '--yes')
    return
}
if (-not (Test-Path $vela)) { throw 'Run topology.ps1 -Action Install first.' }
switch ($Action) {
    'Adopt' {
        # Keep a local preview: Helm manifests can contain sensitive configuration.
        $outputDir = Join-Path $PSScriptRoot 'generated'
        New-Item -ItemType Directory -Force $outputDir | Out-Null
        $adoptArgs = @('adopt', $Release, '--type', 'helm', '--mode', 'read-only',
            '--namespace', $Namespace, '--app-name', $Application)
        $manifest = Invoke-Checked $vela $adoptArgs
        $manifest | Set-Content (Join-Path $outputDir 'application.yaml') -Encoding utf8
        Invoke-Checked $vela ($adoptArgs + @('--apply', '--yes'))
        Invoke-Checked kubectl @('wait', '--for=condition=Ready', "applications.core.oam.dev/$Application", '-n', $Namespace, '--timeout=180s')
        Invoke-Checked $vela @('status', $Application, '-n', $Namespace, '--tree', '--detail')
    }
    'Status' {
        Invoke-Checked $vela @('status', $Application, '-n', $Namespace, '--tree', '--detail')
        Invoke-Checked $vela @('status', $Application, '-n', $Namespace, '--pod')
    }
    'UI' {
        Invoke-Checked $vela @('addon', 'enable', 'velaux', '--version', 'v1.9.5')
        Invoke-Checked $vela @('addon', 'status', 'velaux')
        Write-Host 'Open the dashboard with: kubectl port-forward -n vela-system service/velaux-server 8080:8000 --address=127.0.0.1'
    }
}
