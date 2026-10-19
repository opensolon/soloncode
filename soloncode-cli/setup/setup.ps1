#
# SolonCode CLI Installer for Windows
# Modern PowerShell (3+): irm https://solon.noear.org/soloncode/setup.ps1 | iex
# PowerShell 2.0 (WebClient; no irm):
#   [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072
#   $client = New-Object Net.WebClient
#   try { iex ($client.DownloadString('https://solon.noear.org/soloncode/setup.ps1')) } finally { $client.Dispose() }
# The FIRST HTTPS download already requires OS/.NET TLS 1.2 support and trusted
# certificates; this script cannot fix TLS before it has been downloaded.
# If unavailable, download the release on a supported machine, transfer/extract
# it locally, then run its unified install.ps1 (Java 8+ is still required).
#

$ErrorActionPreference = "Stop"
# Suppress PowerShell's built-in download progress UI (we render our own)
$ProgressPreference = "SilentlyContinue"

$VERSION = "v2026.10.15"
$PACKAGE_URL = "https://gitee.com/opensolon/soloncode/releases/download/$VERSION/soloncode-cli-bin-$VERSION.tar.gz"
$TEMP_DIR = Join-Path $env:TEMP ("soloncode-install-" + [Guid]::NewGuid().ToString("N"))

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] " -ForegroundColor Green -NoNewline
    Write-Host $Message
}

function Write-SetupError {
    param([string]$Message)
    Write-Host "[ERROR] " -ForegroundColor Red -NoNewline
    Write-Host $Message
}

# ---------------------------------------------------------------------------
# Download progress (kept in sync with setup.sh)
# ---------------------------------------------------------------------------

$BAR_WIDTH = 30

function Format-Size {
    param([double]$Bytes)

    if ($Bytes -lt 0) { return "?" }
    if ($Bytes -ge 1GB) { return ("{0:0.00} GB" -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ("{0:0.00} MB" -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ("{0:0.0} KB" -f ($Bytes / 1KB)) }
    return ("{0} B" -f [int]$Bytes)
}

function Write-ProgressBar {
    param([long]$Current, [long]$Total)

    if ($Total -gt 0) {
        $pct = [int](($Current * 100) / $Total)
        if ($pct -gt 100) { $pct = 100 }

        $filled = [int](($pct * $BAR_WIDTH) / 100)
        $bar = '=' * $filled
        if ($filled -lt $BAR_WIDTH) {
            $bar += '>' + (' ' * ($BAR_WIDTH - $filled - 1))
        }

        Write-Host ("`r  [{0}] {1,3}%  {2} / {3}   " -f $bar, $pct, (Format-Size $Current), (Format-Size $Total)) -NoNewline
    } else {
        Write-Host ("`r  Downloaded {0}   " -f (Format-Size $Current)) -NoNewline
    }
}

function Invoke-DownloadWithProgress {
    param([string]$Url, [string]$OutFile)

    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072
    } catch {
        Write-Warning "Cannot enable TLS 1.2. Update OS/.NET, or download/extract the release on another machine and run install.ps1 offline." -WarningAction Continue
        throw
    }

    $response = $null
    $stream = $null
    $file = $null
    try {
        $downloadUri = [Uri]$Url
        # Follow HTTPS redirects only; older .NET may otherwise allow downgrade.
        for ($redirects = 0; ; $redirects++) {
            if ($downloadUri.Scheme -ne 'https') { throw "Refusing non-HTTPS download: $downloadUri" }
            $request = [Net.WebRequest]::Create($downloadUri)
            $request.Timeout = 60000
            $request.UserAgent = "soloncode-setup"
            $request.AllowAutoRedirect = $false
            $response = $request.GetResponse()
            if (@(301, 302, 303, 307, 308) -notcontains [int]$response.StatusCode) { break }
            $location = $response.Headers['Location']
            if (-not $location -or $redirects -ge 10) { throw "Invalid or excessive HTTPS redirects." }
            $downloadUri = New-Object System.Uri -ArgumentList $downloadUri, $location
            $response.Close()
            $response = $null
        }
        $total = $response.ContentLength
        $stream = $response.GetResponseStream()
        $file = [IO.File]::Create($OutFile)
        $buffer = New-Object byte[] 81920
        $downloaded = [long]0
        $lastDraw = 0

        Write-ProgressBar 0 $total

        while ($true) {
            $read = $stream.Read($buffer, 0, $buffer.Length)
            if ($read -le 0) { break }

            $file.Write($buffer, 0, $read)
            $downloaded += $read

            $now = [Environment]::TickCount
            if (($now - $lastDraw) -ge 100) {
                Write-ProgressBar $downloaded $total
                $lastDraw = $now
            }
        }

        if ($total -ge 0 -and $downloaded -ne $total) {
            throw "Incomplete download: expected $total bytes, received $downloaded."
        }
        # Flush/close errors are download failures, not successful completion.
        $file.Close()
        $file = $null
        if ($total -le 0) { $total = $downloaded }
        Write-ProgressBar $downloaded $total
        Write-Host ""
    } finally {
        foreach ($resource in @($file, $stream, $response)) {
            if ($null -ne $resource) {
                try { $resource.Close() }
                catch { Write-Warning "Download resource cleanup failed: $($_.Exception.Message)" -WarningAction Continue }
            }
        }
    }
}

try {
    # PS2 does not imply that tar is installed. Fail before downloading anything.
    $tarCommand = Get-Command tar -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $tarCommand) {
        throw "tar is required to extract tar.gz packages. Provide tar on PATH, or manually extract the release and run install.ps1 offline."
    }
    New-Item -ItemType Directory -Path $TEMP_DIR | Out-Null
    Write-Info "Downloading SolonCode CLI $VERSION..."

    $packageFile = Join-Path $TEMP_DIR "package.tar.gz"
    Invoke-DownloadWithProgress -Url $PACKAGE_URL -OutFile $packageFile

    Write-Info "Extracting package..."

    # Use the preflighted executable; native failures do not throw in PS2.
    & $tarCommand.Definition -xzf $packageFile -C $TEMP_DIR
    if ($LASTEXITCODE -ne 0) {
        throw "Package extraction failed (tar exit code $LASTEXITCODE)."
    }

    # Find the unified installer, treating source paths literally (including []).
    $installScript = Get-ChildItem -LiteralPath $TEMP_DIR -Filter "install.ps1" -Recurse |
        Where-Object { -not $_.PSIsContainer } | Select-Object -First 1

    if (-not $installScript) {
        throw "install.ps1 not found in package"
    }

    Write-Info "Running installer..."

    # Run PowerShell installer
    $installPath = $installScript.FullName
    $installDir = Split-Path $installPath -Parent
    
    Write-Host "Install path: $installPath" -ForegroundColor Gray
    
    $previousSetup = [Environment]::GetEnvironmentVariable('SOLONCODE_SETUP', 'Process')
    $previousInstallDir = [Environment]::GetEnvironmentVariable('SOLONCODE_INSTALL_DIR', 'Process')
    $previousProcessPath = $env:Path
    try {
        $env:SOLONCODE_SETUP = "1"
        # IEX cannot infer the source directory from MyInvocation.
        $env:SOLONCODE_INSTALL_DIR = $installDir

        # Keep the unified installer in a child scope. ReadAllText is PS2-safe,
        # explicitly UTF-8, and treats the source file name literally.
        & {
            param([string]$InstallerPath)
            Invoke-Expression ([IO.File]::ReadAllText($InstallerPath, [Text.Encoding]::UTF8))
            if (-not $?) { throw "install.ps1 failed." }
        } $installPath
    } finally {
        $env:Path = $previousProcessPath
        [Environment]::SetEnvironmentVariable('SOLONCODE_SETUP', $previousSetup, 'Process')
        [Environment]::SetEnvironmentVariable('SOLONCODE_INSTALL_DIR', $previousInstallDir, 'Process')
    }

    # Preserve process-only PATH entries; add only our installed bin, once.
    $installedBin = Join-Path (Join-Path $env:USERPROFILE ".soloncode") "bin"
    $binOnPath = $false
    foreach ($entry in ($env:Path -split ';')) {
        if ($entry.Trim().Trim('"').TrimEnd('\') -ieq $installedBin.TrimEnd('\')) {
            $binOnPath = $true
            break
        }
    }
    if (-not $binOnPath) {
        if ([string]::IsNullOrEmpty($env:Path)) { $env:Path = $installedBin }
        else { $env:Path += ';' + $installedBin }
    }

    Write-Host ""
    Write-Info "Installation complete!"
    Write-Host ""
    Write-Host "You can now run: " -NoNewline
    Write-Host "soloncode cli" -ForegroundColor Cyan -NoNewline
    Write-Host " or " -NoNewline
    Write-Host "soloncode web" -ForegroundColor Cyan
    Write-Host "If SolonCode is already running, soloncode web reuses it and opens the current directory." -ForegroundColor Gray
    Write-Host ""

} catch {
    Write-SetupError $_.Exception.Message
    throw
} finally {
    # Cleanup is best-effort: never replace a download/extraction/install error.
    try {
        if (Test-Path -LiteralPath $TEMP_DIR) {
            Remove-Item -LiteralPath $TEMP_DIR -Recurse -Force -ErrorAction Stop
        }
    } catch {
        Write-Warning "Could not remove temporary directory '$TEMP_DIR': $($_.Exception.Message)" -WarningAction Continue
    }
}
