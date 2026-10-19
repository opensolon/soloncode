#requires -Version 2.0
# Solon Code Installer for Windows PowerShell 2.0-5.1
# 支持重复安装，保留已有 AGENTS.md
$ErrorActionPreference = "Stop"
# Shared by the installation preflight and the generated launcher. No type resolver.
$LOCAL_JSON_FUNCTIONS = @'
function Initialize-LocalJson {
    if ($PSVersionTable.PSVersion.Major -ge 3) { return }
    if ($script:LocalJsonSerializer) { return }
    try {
        try { [void][System.Reflection.Assembly]::Load("System.Web.Extensions") }
        catch { [void][System.Reflection.Assembly]::LoadWithPartialName("System.Web.Extensions") }
        $script:LocalJsonSerializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
        $script:LocalJsonSerializer.MaxJsonLength = 65536
        $script:LocalJsonSerializer.RecursionLimit = 32
    } catch {
        throw "PowerShell 2.0 requires System.Web.Extensions (install .NET Framework 3.5 or use a newer Windows PowerShell): $($_.Exception.Message)"
    }
}

function Assert-LocalJson([string]$Json) {
    if (-not $Json -or $Json.Length -gt 65536) { throw "Local JSON is empty or exceeds 65536 characters." }
    # Bound nesting before either parser, ignoring delimiters inside JSON strings.
    $depth = 0
    $quoted = $false
    $escaped = $false
    foreach ($ch in $Json.ToCharArray()) {
        if ($quoted) {
            if ($escaped) { $escaped = $false }
            elseif ($ch -eq '\') { $escaped = $true }
            elseif ($ch -eq '"') { $quoted = $false }
        } elseif ($ch -eq '"') { $quoted = $true }
        elseif ($ch -eq '{' -or $ch -eq '[') {
            $depth++
            if ($depth -gt 32) { throw "Local JSON exceeds nesting limit 32." }
        } elseif ($ch -eq '}' -or $ch -eq ']') { $depth-- }
    }
}

function ConvertFrom-LocalJson([string]$Json) {
    Assert-LocalJson $Json
    Initialize-LocalJson
    if ($PSVersionTable.PSVersion.Major -ge 3) {
        $value = ConvertFrom-Json -InputObject $Json -ErrorAction Stop
    } else {
        $value = $script:LocalJsonSerializer.DeserializeObject($Json)
    }
    # Do not let PowerShell enumerate a dictionary or JSON array into pipeline output.
    return ,$value
}

function ConvertTo-LocalJson($Value) {
    Initialize-LocalJson
    if ($PSVersionTable.PSVersion.Major -ge 3) {
        $json = ConvertTo-Json -InputObject $Value -Compress -Depth 32 -ErrorAction Stop
    } else { $json = $script:LocalJsonSerializer.Serialize($Value) }
    Assert-LocalJson $json
    return $json
}

function Get-JsonField($Value, [string]$Name) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) { return ,($Value[$Name]) }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $property = $Value.PSObject.Properties[$Name]
        if ($property) { return ,($property.Value) }
        return $null
    }
    throw "Expected a local JSON object."
}
'@
Invoke-Expression $LOCAL_JSON_FUNCTIONS
# PS2 must fail before migration or directory creation if JSON support is absent.
Initialize-LocalJson
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "   Solon Code Installer (PowerShell)" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
# =============================================
# 检查 Java 是否安装
# =============================================
Write-Host "[Pre-check] Verifying Java installation..." -ForegroundColor Yellow
$javaPath = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaPath) {
    Write-Host ""
    Write-Host "[Error] Java is not installed or not in PATH" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Please install Java 8 or later:" -ForegroundColor White
    Write-Host "    - Download from: https://adoptium.net/" -ForegroundColor White
    Write-Host ""
    if ($env:SOLONCODE_SETUP) { throw "Java is not installed or not in PATH" }
    Read-Host "Press Enter to exit"
    exit 1
}
# 获取 Java 版本 (java -version 输出到 stderr，使用 .NET 进程避免 PowerShell 错误显示)
$process = New-Object System.Diagnostics.Process
$process.StartInfo.FileName = "java"
$process.StartInfo.Arguments = "-version"
$process.StartInfo.RedirectStandardError = $true
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.UseShellExecute = $false
$process.Start() | Out-Null
$javaVersionOutput = $process.StandardError.ReadToEnd()
$process.WaitForExit()
$javaVersion = ($javaVersionOutput -split "`n" | Where-Object { $_ -match "version" } | Select-Object -First 1).Trim()
Write-Host "      $javaVersion" -ForegroundColor Gray
Write-Host ""
# =============================================
# 设置源目录和目标目录
# =============================================
# When install.ps1 is executed via Get-Content | Invoke-Expression (from setup.ps1),
# $MyInvocation.MyCommand.Definition doesn't return the correct file path.
# In that case, setup.ps1 sets $env:SOLONCODE_INSTALL_DIR to the correct directory.
if ($env:SOLONCODE_INSTALL_DIR -and (Test-Path -LiteralPath $env:SOLONCODE_INSTALL_DIR)) {
    $SOURCE_DIR = $env:SOLONCODE_INSTALL_DIR
} else {
    $SOURCE_DIR = Split-Path -Parent $MyInvocation.MyCommand.Definition
    if (-not $SOURCE_DIR -or -not (Test-Path -LiteralPath $SOURCE_DIR)) { $SOURCE_DIR = $PWD.Path }
}
$SOURCE_BIN_DIR = Join-Path $SOURCE_DIR "bin"
$SOURCE_SKILLS_DIR = Join-Path $SOURCE_DIR "skills"
$SOURCE_COMMANDS_DIR = Join-Path $SOURCE_DIR "commands"
$SOURCE_AGENTS = Join-Path $SOURCE_DIR "AGENTS.md"
$TARGET_DIR = Join-Path $env:USERPROFILE ".soloncode"
$TARGET_BIN_DIR = Join-Path $TARGET_DIR "bin"
$TARGET_SKILLS_DIR = Join-Path $TARGET_DIR "skills"
$TARGET_COMMANDS_DIR = Join-Path $TARGET_DIR "commands"
$TARGET_AGENTS = Join-Path $TARGET_DIR "AGENTS.md"
$OLD_TARGET_AGENTS = Join-Path $TARGET_BIN_DIR "AGENTS.md"
# =============================================
# 检查源目录是否存在
# =============================================
if (-not (Test-Path -LiteralPath $SOURCE_BIN_DIR)) {
    Write-Host "[Error] Source bin directory not found: $SOURCE_BIN_DIR" -ForegroundColor Red
    if ($env:SOLONCODE_SETUP) { throw "Source bin directory not found: $SOURCE_BIN_DIR" }
    Read-Host "Press Enter to exit"
    exit 1
}
$sourceJar = Join-Path $SOURCE_BIN_DIR "soloncode-cli.jar"
if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) { throw "Source jar not found: $sourceJar" }
$sourceRoot = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $SOURCE_DIR).Path).TrimEnd('\', '/')
$targetRoot = [System.IO.Path]::GetFullPath($TARGET_DIR).TrimEnd('\', '/')
if ([string]::Equals($sourceRoot, $targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Installation source and target must be different directories: $SOURCE_DIR"
}
# =============================================
# [1/5] 保留已有的 AGENTS.md，并迁移旧版本文件
# =============================================
Write-Host "[1/5] Checking for existing AGENTS.md..." -ForegroundColor Yellow

# 迁移旧版本的 AGENTS.md（从 bin/ 目录移动到根目录）
if ((Test-Path -LiteralPath $OLD_TARGET_AGENTS) -and -not (Test-Path -LiteralPath $TARGET_AGENTS)) {
    Move-Item -LiteralPath $OLD_TARGET_AGENTS -Destination $TARGET_AGENTS -Force
    Write-Host "      Migrated AGENTS.md from bin/ to root directory" -ForegroundColor Gray
}

# 已有文件始终留在原位，不先覆盖再恢复
if (Test-Path -LiteralPath $TARGET_AGENTS) {
    Write-Host "      Found existing AGENTS.md (will be preserved)" -ForegroundColor Gray
} else {
    Write-Host "      No existing AGENTS.md found" -ForegroundColor Gray
}
# =============================================
# [2/5] 创建目标目录结构
# =============================================
Write-Host ""
Write-Host "[2/5] Preparing target directory: $TARGET_DIR" -ForegroundColor Yellow
# Directory.CreateDirectory treats brackets literally (New-Item has no LiteralPath).
[void][System.IO.Directory]::CreateDirectory($TARGET_DIR)
[void][System.IO.Directory]::CreateDirectory($TARGET_BIN_DIR)
[void][System.IO.Directory]::CreateDirectory($TARGET_SKILLS_DIR)
[void][System.IO.Directory]::CreateDirectory($TARGET_COMMANDS_DIR)
Write-Host "      Created directory structure" -ForegroundColor Gray
# =============================================
# [3/5] 复制文件
# =============================================
Write-Host ""
Write-Host "[3/5] Copying files to target directory..." -ForegroundColor Yellow
# 复制 bin 目录内容
Get-ChildItem -LiteralPath $SOURCE_BIN_DIR -Force | Where-Object { $_.Name -ne "AGENTS.md" } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $TARGET_BIN_DIR -Recurse -Force
}
Write-Host "      Copied bin/ directory" -ForegroundColor Gray

# 复制 AGENTS.md（从根目录）
if (-not (Test-Path -LiteralPath $TARGET_AGENTS) -and (Test-Path -LiteralPath $SOURCE_AGENTS)) {
    Copy-Item -LiteralPath $SOURCE_AGENTS -Destination $TARGET_AGENTS
    Write-Host "      Copied AGENTS.md" -ForegroundColor Gray
}
# 复制 skills 目录（仅同名目录替换，保留用户自行安装的 skill）
if (Test-Path -LiteralPath $SOURCE_SKILLS_DIR -PathType Container) {
    # 仅遍历安装包自带的 skill 子目录，逐个替换同名目录
    Get-ChildItem -LiteralPath $SOURCE_SKILLS_DIR -Force | Where-Object { $_.PSIsContainer } | ForEach-Object {
        $skillName = $_.Name
        $targetSkillPath = Join-Path $TARGET_SKILLS_DIR $skillName
        # 删除目标中的同名 skill 目录后再复制（不影响其他用户 skill）
        if (Test-Path -LiteralPath $targetSkillPath) {
            Remove-Item -LiteralPath $targetSkillPath -Recurse -Force
        }
        Copy-Item -LiteralPath $_.FullName -Destination $targetSkillPath -Recurse -Force
        Write-Host "      Updated skill: $skillName" -ForegroundColor Gray
    }
} else {
    Write-Host "      No skills/ directory to copy" -ForegroundColor Gray
}

# 复制 commands 目录（仅替换安装包自带的同名命令文件，保留用户自建命令）
if (Test-Path -LiteralPath $SOURCE_COMMANDS_DIR -PathType Container) {
    $sourceCommandsRoot = (Resolve-Path -LiteralPath $SOURCE_COMMANDS_DIR).Path
    Get-ChildItem -LiteralPath $SOURCE_COMMANDS_DIR -Recurse -Force | Where-Object { -not $_.PSIsContainer } | ForEach-Object {
        $relativeCommandPath = $_.FullName.Substring($sourceCommandsRoot.Length + 1)
        $targetCommandPath = Join-Path $TARGET_COMMANDS_DIR $relativeCommandPath
        $targetCommandParent = Split-Path -Parent $targetCommandPath

        [void][System.IO.Directory]::CreateDirectory($targetCommandParent)
        if (Test-Path -LiteralPath $targetCommandPath -PathType Container) {
            throw "Command target is a directory: $targetCommandPath"
        }

        Copy-Item -LiteralPath $_.FullName -Destination $targetCommandPath -Force
        Write-Host "      Updated command: $relativeCommandPath" -ForegroundColor Gray
    }
} else {
    Write-Host "      No commands/ directory to copy" -ForegroundColor Gray
}
Write-Host "      Files copied successfully" -ForegroundColor Green
# =============================================
# [4/5] 清理旧位置并检查 jar 文件
# =============================================
Write-Host ""
Write-Host "[4/5] Finalizing installation..." -ForegroundColor Yellow

# 清理旧位置的 AGENTS.md（如果还存在）
if (Test-Path -LiteralPath $OLD_TARGET_AGENTS) {
    Remove-Item -LiteralPath $OLD_TARGET_AGENTS -Force
}
# 检查 jar 文件是否存在
$JAR_FILE = Join-Path $TARGET_BIN_DIR "soloncode-cli.jar"
if (-not (Test-Path -LiteralPath $JAR_FILE)) {
    Write-Host ""
    Write-Host "[Error] soloncode-cli.jar not found in $TARGET_BIN_DIR" -ForegroundColor Red
    if ($env:SOLONCODE_SETUP) { throw "soloncode-cli.jar not found in $TARGET_BIN_DIR" }
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host "      Found soloncode-cli.jar" -ForegroundColor Gray
# =============================================
# [5/5] 创建启动脚本并配置 PATH
# =============================================
Write-Host ""
Write-Host "[5/5] Setting up 'soloncode' command..." -ForegroundColor Yellow
# 创建 PowerShell 启动脚本 (soloncode.ps1)
$LAUNCHER_PS1 = Join-Path $TARGET_BIN_DIR "soloncode.ps1"
$LAUNCHER_CONTENT = @'
#requires -Version 2.0
# Solon Code CLI Launcher for PowerShell. Web 先判断端口，再决定是否启动 Java。
# Keep CLI flags (including PowerShell common parameter names) untouched.
$RestArgs = @($args)
'@ + "`r`n" + $LOCAL_JSON_FUNCTIONS + "`r`n" + @'
$JarDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$JarFile = Join-Path $JarDir "soloncode-cli.jar"
if (-not (Test-Path -LiteralPath $JarFile)) { throw "soloncode-cli.jar not found: $JarFile" }

function Invoke-LocalJson([string]$Uri, [int]$TimeoutMs, [string]$Token, [byte[]]$Body) {
    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $response = $null
    $reader = $null
    try {
        if ($null -ne $Body) {
            $request.Method = "POST"
            $request.ContentType = "application/json; charset=utf-8"
            $request.Headers["X-Soloncode-Attach-Token"] = $Token
            $request.ContentLength = $Body.Length
            $stream = $request.GetRequestStream()
            try { $stream.Write($Body, 0, $Body.Length) } finally { $stream.Close() }
        }
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) { throw "Unexpected HTTP status: $($response.StatusCode)" }
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $text = New-Object System.Text.StringBuilder
        $buffer = New-Object char[] 4096
        while (($count = $reader.Read($buffer, 0, $buffer.Length)) -gt 0) {
            if ($text.Length + $count -gt 65536) { throw "Local JSON response exceeds 65536 characters." }
            [void]$text.Append($buffer, 0, $count)
        }
        return ,(ConvertFrom-LocalJson ($text.ToString()))
    } catch {
        $errorResponse = $_.Exception
        while ($errorResponse) {
            if ($errorResponse -is [System.Net.WebException] -and $errorResponse.Response) { $errorResponse.Response.Close() }
            $errorResponse = $errorResponse.InnerException
        }
        throw
    } finally {
        if ($reader) { $reader.Close() }
        if ($response) { $response.Close() }
        $request.Abort()
    }
}

function Test-LocalPort([int]$Port) {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $waitHandle = $null
    try {
        if ($null -ne $tcp.PSObject.Methods["ConnectAsync"]) {
            $connectTask = $tcp.ConnectAsync("localhost", $Port)
            $completed = $connectTask.Wait(500)
        } else {
            $pending = $tcp.BeginConnect("localhost", $Port, $null, $null)
            $waitHandle = $pending.AsyncWaitHandle
            $completed = $waitHandle.WaitOne(500, $false)
            if ($completed) { [void]$tcp.EndConnect($pending) }
        }
        if (-not $completed) { throw "Connection to port $Port timed out; refusing to start another instance." }
        if (-not $tcp.Connected) { throw "Could not determine whether port $Port is available." }
        return $true
    } catch {
        $cause = $_.Exception
        while ($cause.InnerException) { $cause = $cause.InnerException }
        # Only an explicit refusal means the port is idle. Timeout is not idle.
        if ($cause -is [System.Net.Sockets.SocketException] -and $cause.SocketErrorCode -eq [System.Net.Sockets.SocketError]::ConnectionRefused) { return $false }
        throw
    } finally {
        $tcp.Close()
        if ($waitHandle) { $waitHandle.Close() }
    }
}

function Open-ExistingWeb([int]$Port, [string]$Path) {
    if (-not (Test-LocalPort $Port)) { return $false }
    # Only a live instance handoff requires JSON support at launcher runtime.
    Initialize-LocalJson
    try { $info = Invoke-LocalJson -Uri "http://localhost:$Port/web/instance/info" -TimeoutMs 2000 }
    catch { throw "Could not verify SolonCode on port $Port; refusing to start another instance: $($_.Exception.Message)" }
    $data = Get-JsonField $info "data"
    if ($null -eq $data) { $data = $info }
    $product = Get-JsonField $data "product"
    $protocol = Get-JsonField $data "protocol"
    $attachSupported = Get-JsonField $data "attachSupported"
    $numericProtocol = $protocol -is [int] -or $protocol -is [long] -or $protocol -is [decimal] -or $protocol -is [double]
    if ($product -isnot [string] -or $product -ne "soloncode" -or -not $numericProtocol -or $protocol -ne 1 -or $attachSupported -isnot [bool] -or -not $attachSupported) {
        throw "Port $Port is occupied by another service, not SolonCode."
    }
    $record = Join-Path (Join-Path $env:USERPROFILE ".soloncode") "web-instance-$Port.json"
    if (-not (Test-Path -LiteralPath $record)) { throw "SolonCode is already running on port $Port, but its local handoff record is unavailable." }
    $recordData = ConvertFrom-LocalJson ([System.IO.File]::ReadAllText($record, [System.Text.Encoding]::UTF8))
    $token = Get-JsonField $recordData "token"
    if ($token -isnot [string] -or -not $token.Trim()) { throw "SolonCode handoff record contains no token." }
    $payload = [System.Text.Encoding]::UTF8.GetBytes((ConvertTo-LocalJson @{ path = $Path }))
    try { $result = Invoke-LocalJson -Uri "http://localhost:$Port/web/instance/attach" -TimeoutMs 5000 -Token $token -Body $payload }
    catch { throw "Could not open the current directory in the existing SolonCode instance: $($_.Exception.Message)" }
    $resultData = Get-JsonField $result "data"
    $workspaceId = Get-JsonField $resultData "workspaceId"
    if ($null -eq $workspaceId) { $workspaceId = Get-JsonField $result "workspaceId" }
    if ($workspaceId -isnot [string] -or -not $workspaceId.Trim()) { throw "Existing SolonCode returned no workspace id." }
    $url = "http://localhost:$Port/?workspaceId=$([Uri]::EscapeDataString([string]$workspaceId))"
    try { Start-Process $url -ErrorAction Stop }
    catch { Write-Host "Could not launch the browser. Open this URL manually: $url" }
    return $true
}

$helpRequested = @($RestArgs | Where-Object { @("--help", "-h", "-?", "/?") -contains $_ }).Count -gt 0
if (-not $helpRequested -and $RestArgs.Count -gt 0 -and $RestArgs[0] -eq "web") {
    $port = 4808
    $validPort = $RestArgs.Count -le 1 -or [int]::TryParse([string]$RestArgs[1], [ref]$port)
    if ($validPort -and $port -gt 0 -and $port -le 65535) { if (Open-ExistingWeb $port ((Get-Location).Path)) { exit 0 } }
}

try { $OutputEncoding = [System.Text.Encoding]::UTF8; [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$JavaArgs = @("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8")
try {
    $v = (java -version 2>&1 | Select-Object -First 1)
    if ($v -match '"(\d+)' -and [int]$Matches[1] -ge 21) { $JavaArgs += "--enable-native-access=ALL-UNNAMED" }
} catch { }
& java @JavaArgs -jar $JarFile @RestArgs
exit $LASTEXITCODE
'@
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$ps1Text = ($LAUNCHER_CONTENT -replace "`r`n", "`n").TrimEnd([char[]]"`r`n") -replace "`n", "`r`n"
[System.IO.File]::WriteAllText($LAUNCHER_PS1, $ps1Text + "`r`n", $utf8Bom)
Write-Host "      Created: soloncode.ps1" -ForegroundColor Gray
# 创建 CMD/.bat 启动脚本 (soloncode.bat)
$LAUNCHER_BAT = Join-Path $TARGET_BIN_DIR "soloncode.bat"
$LAUNCHER_BAT_CONTENT = @'
@echo off
rem Delegate to the shared PowerShell launcher.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0soloncode.ps1" %*
exit /b %ERRORLEVEL%
'@
$batText = ($LAUNCHER_BAT_CONTENT -replace "`r`n", "`n").TrimEnd([char[]]"`r`n") -replace "`n", "`r`n"
[System.IO.File]::WriteAllText($LAUNCHER_BAT, $batText + "`r`n", [System.Text.Encoding]::ASCII)
Write-Host "      Created: soloncode.bat" -ForegroundColor Gray
# 创建 Git Bash 启动脚本 (soloncode)
$LAUNCHER_SH = Join-Path $TARGET_BIN_DIR "soloncode"
$LAUNCHER_SH_CONTENT = @'
#!/bin/bash
# Git Bash 使用同一份 PowerShell 启动逻辑，避免与 CMD/PowerShell 分叉。
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/soloncode.ps1" "$@"
'@
$bashText = ($LAUNCHER_SH_CONTENT -replace "`r`n", "`n").TrimEnd([char[]]"`r`n") + "`n"
[System.IO.File]::WriteAllText($LAUNCHER_SH, $bashText, $utf8NoBom)
Write-Host "      Created: soloncode (for Git Bash)" -ForegroundColor Gray
# =============================================
# 配置 PATH 环境变量
# =============================================
Write-Host ""
Write-Host "Configuring PATH..." -ForegroundColor Yellow
# Read the raw value and preserve its registry type and unrelated entries.
$USER_ENV = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey("Environment", $true)
try {
    if ($null -eq $USER_ENV) {
        $USER_ENV = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey("Environment")
    }
    $USER_PATH = $USER_ENV.GetValue("Path", $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    $PATH_KIND = [Microsoft.Win32.RegistryValueKind]::String
    if ($null -ne $USER_PATH) {
        $PATH_KIND = $USER_ENV.GetValueKind("Path")
        if ($PATH_KIND -ne [Microsoft.Win32.RegistryValueKind]::String -and
            $PATH_KIND -ne [Microsoft.Win32.RegistryValueKind]::ExpandString) {
            throw "User PATH is not a string value; refusing to rewrite it."
        }
    }
    # 检查是否已在 PATH 中（仅比较完整项）
    $pathContainsTarget = @($USER_PATH -split ';' | Where-Object {
        [string]::Equals($_, $TARGET_BIN_DIR, [System.StringComparison]::OrdinalIgnoreCase)
    }).Count -gt 0
    if ($pathContainsTarget) {
        Write-Host "      Already in user PATH" -ForegroundColor Gray
    } else {
        # 添加到用户 PATH
        $NEW_PATH = if ($USER_PATH) { "$USER_PATH;$TARGET_BIN_DIR" } else { $TARGET_BIN_DIR }
        $USER_ENV.SetValue("Path", $NEW_PATH, $PATH_KIND)
        $USER_ENV.Flush()
        Write-Host "      Added to user PATH" -ForegroundColor Green
    }
} finally {
    if ($null -ne $USER_ENV) {
        $USER_ENV.Close()
    }
}
# =============================================
# 完成
# =============================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "   Installation Complete!" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Install path: $TARGET_DIR" -ForegroundColor White
Write-Host "  Java version: $javaVersion" -ForegroundColor White
Write-Host ""
Write-Host "  Usage:" -ForegroundColor Cyan
Write-Host "    1. Sign out and back in so all new terminals receive the updated PATH"
Write-Host "    2. Run: 'soloncode cli' or 'soloncode web'"
Write-Host ""
Write-Host "  Directory structure:" -ForegroundColor Cyan
Write-Host "    $env:USERPROFILE\.soloncode\"
Write-Host "    +-- AGENTS.md       (agents config, preserved)"
Write-Host "    +-- bin/            (executables)"
Write-Host "    |   +-- soloncode-cli.jar"
Write-Host "    |   +-- soloncode.ps1   (PowerShell launcher)"
Write-Host "    |   +-- soloncode.bat   (CMD launcher)"
Write-Host "    |   +-- soloncode       (Git Bash launcher)"
Write-Host "    |   +-- uninstall.ps1   (uninstall script)"
Write-Host "    |   +-- uninstall.cmd   (uninstall script)"
Write-Host "    +-- skills/         (skill modules)"
Write-Host "    +-- commands/       (custom commands)"
Write-Host ""
Write-Host "  [Tip] To use soloncode immediately in current terminal:" -ForegroundColor Yellow
Write-Host "    PowerShell: `$env:Path += ';' + '$($TARGET_BIN_DIR.Replace("'", "''"))'"
Write-Host ""
# If not called from setup.ps1, wait for user input
if (-not $env:SOLONCODE_SETUP) {
    Read-Host "Press Enter to exit"
}
