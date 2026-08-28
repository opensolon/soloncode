#
# SolonCode CLI Installer for Windows
# Usage: irm https://solon.noear.org/soloncode/setup.ps1 | iex
#

$ErrorActionPreference = "Stop"
# Suppress PowerShell's built-in download progress UI (we render our own)
$ProgressPreference = "SilentlyContinue"

$VERSION = "v2026.8.28"
$PACKAGE_URL = "https://gitee.com/opensolon/soloncode/releases/download/$VERSION/soloncode-cli-bin-$VERSION.tar.gz"
$TEMP_DIR = Join-Path $env:TEMP "soloncode-install"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] " -ForegroundColor Green -NoNewline
    Write-Host $Message
}

function Write-Error {
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
    } catch { }

    $request = [Net.WebRequest]::Create($Url)
    $request.Timeout = 60000
    if ($request -is [Net.HttpWebRequest]) {
        $request.UserAgent = "soloncode-setup"
        $request.AllowAutoRedirect = $true
    }

    $response = $request.GetResponse()
    $total = $response.ContentLength
    $stream = $response.GetResponseStream()
    $file = [IO.File]::Create($OutFile)

    try {
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

        if ($total -le 0) { $total = $downloaded }
        Write-ProgressBar $downloaded $total
        Write-Host ""
    } finally {
        $file.Close()
        $stream.Close()
        $response.Close()
    }
}

# Cleanup temp directory
if (Test-Path $TEMP_DIR) {
    Remove-Item -Recurse -Force $TEMP_DIR
}
New-Item -ItemType Directory -Path $TEMP_DIR | Out-Null

try {
    Write-Info "Downloading SolonCode CLI $VERSION..."

    $packageFile = Join-Path $TEMP_DIR "package.tar.gz"
    Invoke-DownloadWithProgress -Url $PACKAGE_URL -OutFile $packageFile

    Write-Info "Extracting package..."

    # Extract tar.gz using built-in tar (Windows 10+)
    tar -xzf $packageFile -C $TEMP_DIR

    # Find install.ps1
    $installScript = Get-ChildItem -Path $TEMP_DIR -Filter "install.ps1" -Recurse | Select-Object -First 1

    if (-not $installScript) {
        Write-Error "install.ps1 not found in package"
        exit 1
    }

    Write-Info "Running installer..."

    # Run PowerShell installer
    $installPath = $installScript.FullName
    $installDir = Split-Path $installPath -Parent
    
    Write-Host "Install path: $installPath" -ForegroundColor Gray
    
    # Set environment variable to tell install.ps1 not to wait
    $env:SOLONCODE_SETUP = "1"
    
    # Set environment variable to pass the source directory (because $MyInvocation.MyCommand.Definition
    # doesn't work correctly when script is executed via Get-Content | Invoke-Expression)
    $env:SOLONCODE_INSTALL_DIR = $installDir
    
    # Execute the installer script via Invoke-Expression to bypass execution policy
    # Using & triggers PowerShell's execution policy check (Restricted), causing "禁止运行脚本" error
    Get-Content -Path $installPath -Raw | Invoke-Expression

    # Refresh PATH for current session
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $env:Path = $env:Path.TrimEnd(';')

    Write-Host ""
    Write-Info "Installation complete!"
    Write-Host ""
    Write-Host "You can now run: " -NoNewline
    Write-Host "soloncode cli" -ForegroundColor Cyan -NoNewline
    Write-Host " or " -NoNewline
    Write-Host "soloncode web 0" -ForegroundColor Cyan
    Write-Host ""

} catch {
    Write-Error $_.Exception.Message
    throw $_
}
