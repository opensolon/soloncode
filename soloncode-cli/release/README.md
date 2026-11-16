# 离线安装说明

### 1、系统要求

* Java 8 或更高版本（支持 Java 8 到 Java 26 环境，需要提前安装好）。
* 支持 macOS、Linux、Windows。

### 2、“安装” 或 “更新”脚本

Mac / Linux / Harmony PC：

```bash
sh install.sh
```

macOS 还可以直接双击 `install.mac.command`（会在“终端”里跑 `install.sh`；装完按回车直接在这个窗口里启动 `soloncode web`，装完就能用，Ctrl+C 停止）。

Windows (PowerShell)：

```powershell
& "./install.ps1"
```

Windows 离线安装器及生成的启动器以 Windows PowerShell **2.0～5.1** 为兼容目标；`install.ps1` 是唯一正式实现，`install-ps2.ps1` 仅保留为旧入口，需与它放在同一目录。

* PowerShell 3.0～5.1 使用内置 JSON 支持；PowerShell 2.0 需要能加载 `System.Web.Extensions`（通常由 .NET Framework 3.5 提供），安装器会在修改文件前检查，缺失时明确报错。
* 如果执行策略阻止脚本，可以运行同包的 `install.cmd`；安装后重新打开终端。
* 脚本兼容目标不等于产品已通过 Server 2008 认证：JDK、系统组件、浏览器及在线下载链路需另行确认。本次兼容改造不覆盖在线 `setup.ps1` 和卸载器。
* 当前尚未完成 Windows PowerShell 2.0～5.1 实机矩阵验证，不能将静态检查视为兼容性认证。

安装脚本会把程序文件写入 `~/.soloncode/`，并尝试将 `soloncode` 命令注册到 PATH。


### 3、免安装运行（便携模式）

不想“安装”也可以直接用发布包里的 `run.sh` / `run.cmd` 启动，它们只做一件事：用 Java 运行同包的 `bin/soloncode-cli.jar`。

不带任何参数时默认启动 **Web 界面**（默认端口 4808，并自动打开浏览器），和双击启动的效果一致；想要交互式命令行，显式传 `cli`。

Mac / Linux / Harmony PC：

```bash
sh run.sh                      # Web 界面（默认端口 4808，自动打开浏览器）
sh run.sh web 4808             # Web 界面并指定端口
sh run.sh cli                  # 交互式命令行
sh run.sh --help               # 查看全部参数
```

Windows：

```bat
run.cmd
run.cmd web 4808
run.cmd cli
```

* Windows 和 macOS 都可以直接双击启动，不用开命令行：Windows 双击 `run.cmd`（程序退出后窗口会停住，方便看提示）；macOS 双击 `run.mac.command`（会在“终端”里运行 `run.sh`，因为 Finder 里双击 `.sh` 只会用编辑器打开）。
* 双击时的工作目录是发布包所在目录，也就是 CLI 默认使用的工作区；想换个目录，就在终端里先 `cd` 到目标目录再执行 `sh /path/to/run.sh`。
* 如果 macOS 提示“无法验证开发者”，执行一次：`xattr -d com.apple.quarantine run.mac.command install.mac.command run.sh install.sh`。

* 这个包可以放在 U 盘、网络共享目录等任意位置，换台机器（对方机器有 Java 8+）就能跑。
* 默认情况下数据仍然写到用户主目录（`~/.soloncode`，Windows 为 `%USERPROFILE%\.soloncode`），和安装版共用同一份配置。
* 想让配置、会话、记忆、技能、子代理都跟着程序走（真正“随处运行”），把 `SOLONCODE_HOME` 指向一个可写目录即可，程序会在其下创建 `.soloncode/`：

```bash
export SOLONCODE_HOME="/media/usb/soloncode-data"   # Windows: set SOLONCODE_HOME=E:\soloncode-data
sh run.sh web
```

* 注意：包根的 `AGENTS.md`、`skills/`、`commands/` 是给安装脚本用的，程序运行时只从 `~/.soloncode/`（便携模式下为 `$SOLONCODE_HOME/.soloncode/`）读取。想在新环境里预置，把它们复制到该目录下即可，例如便携模式：

```bash
mkdir -p "$SOLONCODE_HOME/.soloncode"
cp -r skills commands AGENTS.md "$SOLONCODE_HOME/.soloncode/"
```
* 免安装启动器比安装版生成的 `soloncode` 命令更简单：不做“复用已在运行实例并把当前目录打开为工作区”的端口探测，`web` 端口被占用时会直接报错，不会自动接管（所以不要重复双击；先关掉前一个窗口，或改用 `sh run.sh web 0` 让程序自选空闲端口）。


### 4、安装后的主要目录

```text
~/.soloncode/
+-- AGENTS.md              # 全局智能体提示词
+-- settings.json          # Web 设置页维护的全局配置
+-- bin/
|   +-- soloncode-cli.jar
|   +-- soloncode
|   +-- soloncode.ps1
|   +-- uninstall.sh
|   +-- uninstall.mac.command
|   +-- uninstall.ps1
|   +-- uninstall.window.cmd
+-- skills/                # 全局 Skills
+-- agents/                # 全局子代理
+-- commands/              # 全局自定义命令
+-- extensions/            # Java 扩展插件
+-- memory/                # 全局长期记忆（默认）
```


### 5、首次配置模型（使用 Web 设置页）

```bash
soloncode web
```

打开浏览器后进入“设置 -> 大语言模型”，添加模型并测试连接。推荐新用户优先使用这种方式。

使用固定端口时，如果该端口已经运行 SolonCode，命令会复用已有实例，并把当前启动目录打开为一个工作区：

```bash
soloncode web
```

如果固定端口被其他服务占用，命令会明确报错。


### 6、如何卸载

Mac / Linux：

```bash
sh ~/.soloncode/bin/uninstall.sh
```

macOS 也可以直接双击 `~/.soloncode/bin/uninstall.mac.command`（会在“终端”里跑 `uninstall.sh`，卸载完按回车关闭窗口）。卸载前请先关掉正在运行的 Web / CLI 窗口，否则进程会残留。

Windows (PowerShell)：

```powershell
& "$HOME/.soloncode/bin/uninstall.ps1"
```
