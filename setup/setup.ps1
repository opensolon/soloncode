#
# SolonCode CLI Installer for Windows
# Usage: irm https://solon.noear.org/soloncode/setup.ps1 | iex
#

$ErrorActionPreference = "Stop"

$VERSION = "v2026.4.1"
$PACKAGE_URL = "https://solon.noear.org/soloncode/bin/soloncode-cli-bin-$VERSION.tar.gz"
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

# Cleanup temp directory
if (Test-Path "$TEMP_DIR") {
    Remove-Item -Recurse -Force "$TEMP_DIR"
}
New-Item -ItemType Directory -Path "$TEMP_DIR" | Out-Null

try {
    Write-Info "Downloading SolonCode CLI $VERSION..."

    $packageFile = Join-Path $TEMP_DIR "package.tar.gz"
    Invoke-WebRequest -Uri $PACKAGE_URL -OutFile "$packageFile" -UseBasicParsing

    Write-Info "Extracting package..."

    # Extract tar.gz using built-in tar (Windows 10+)
    # Use quotes to handle paths with spaces
    tar -xzf "$packageFile" -C "$TEMP_DIR"

    # Find install.cmd
    $installScript = Get-ChildItem -Path "$TEMP_DIR" -Filter "install.cmd" -Recurse | Select-Object -First 1

    if (-not $installScript) {
        Write-Error "install.cmd not found in package"
        exit 1
    }

    Write-Info "Running installer..."

    # Run installer (with quotes to handle paths with spaces)
    $installDir = Split-Path $installScript.FullName -Parent
    Push-Location $installDir
    & cmd /c """$($installScript.FullName)"""
    Pop-Location

} catch {
    Write-Error $_.Exception.Message
    exit 1
}