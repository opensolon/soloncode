#
# SolonCode CLI Installer for Windows
# Usage: irm https://solon.noear.org/soloncode/setup.ps1 | iex
#

$ErrorActionPreference = "Stop"

$VERSION = "v2026.5.3"
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

# Cleanup temp directory
if (Test-Path $TEMP_DIR) {
    Remove-Item -Recurse -Force $TEMP_DIR
}
New-Item -ItemType Directory -Path $TEMP_DIR | Out-Null

try {
    Write-Info "Downloading SolonCode CLI $VERSION..."

    $packageFile = Join-Path $TEMP_DIR "package.tar.gz"
    Invoke-WebRequest -Uri $PACKAGE_URL -OutFile $packageFile -UseBasicParsing

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
    
    # Execute the installer script
    & $installPath
    
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne $null) {
        Write-Error "Installer failed with exit code: $LASTEXITCODE"
        throw "Installation failed"
    }

    # Refresh PATH for current session
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $env:Path = $env:Path.TrimEnd(';')

    Write-Host ""
    Write-Info "Installation complete!"
    Write-Host ""
    Write-Host "You can now run: " -NoNewline
    Write-Host "soloncode" -ForegroundColor Cyan
    Write-Host ""

} catch {
    Write-Error $_.Exception.Message
    throw $_
}
