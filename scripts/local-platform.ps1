param(
    [ValidateSet('start', 'stop', 'status')][string]$Action = 'start',
    [string]$EnvFile,
    [ValidateRange(1, 3600)][int]$TimeoutSeconds = 180,
    [switch]$NoBrowser
)

$script:RepositoryRoot = Split-Path $PSScriptRoot -Parent

function Start-DockerDesktop {
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop)) {
        throw 'Install Docker Desktop and enable Linux containers, then try again.'
    }
    Start-Process -FilePath $desktop -WindowStyle Hidden
}

function Get-DockerEngine {
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $engine = docker info --format '{{.OSType}}' 2>$null
        if ($LASTEXITCODE -eq 0) { return "$engine".Trim() }
        return ''
    } finally { $ErrorActionPreference = $previous }
}

function Invoke-LocalPlatform {
    param(
        [ValidateSet('start', 'stop', 'status')][string]$Action = 'start',
        [string]$EnvFile,
        [ValidateRange(1, 3600)][int]$TimeoutSeconds = 180,
        [switch]$NoBrowser
    )
    $ErrorActionPreference = 'Stop'
    if (-not $EnvFile) { $EnvFile = Join-Path $script:RepositoryRoot '.tools\local\.env' }
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
        throw 'Deployment configuration is missing. Follow docs/guides/quickstart.md for initial setup, or pass -EnvFile with the existing .env path.'
    }
    $EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
    $engine = Get-DockerEngine
    if (-not $engine -and $Action -eq 'start') {
        Write-Host 'Starting Docker Desktop...'
        Start-DockerDesktop
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $engine = Get-DockerEngine
            if ($engine) { break }
            Start-Sleep -Seconds 2
        } while ([DateTime]::UtcNow -lt $deadline)
    }
    if ($engine -ne 'linux') { throw 'Docker Linux engine is unavailable. Open Docker Desktop and select Linux containers.' }
    $compose = @('compose', '--env-file', $EnvFile, '-f', (Join-Path $script:RepositoryRoot 'deploy\compose.yaml'))
    switch ($Action) {
        'start' { $command = @('up', '--detach', '--wait', '--wait-timeout', "$TimeoutSeconds", '--no-build', '--pull', 'never') }
        'stop' { $command = @('stop') }
        'status' { $command = @('ps') }
    }
    & docker @compose @command
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose $Action failed (exit $LASTEXITCODE). Check Docker output above." }
    if ($Action -eq 'start') {
        $port = '8443'
        foreach ($line in Get-Content -LiteralPath $EnvFile) {
            if ($line -match '^\s*HTTPS_PORT\s*=\s*["'']?(\d+)["'']?\s*$') { $port = $Matches[1] }
        }
        $url = "https://localhost:$port"
        Write-Host "VeyraEval is ready: $url"
        if (-not $NoBrowser) { Start-Process $url }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        Invoke-LocalPlatform -Action $Action -EnvFile $EnvFile -TimeoutSeconds $TimeoutSeconds -NoBrowser:$NoBrowser
    } catch {
        Write-Host $_.Exception.Message -ForegroundColor Red
        exit 1
    }
}
