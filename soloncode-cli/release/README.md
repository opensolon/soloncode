# 离线安装说明

### 1、系统要求

* Java 8 或更高版本（支持 Java 8 到 Java 26 环境，需要提前安装好）。
* 支持 macOS、Linux、Windows。

### 2、“安装” 或 “更新”脚本

Mac / Linux / Harmony PC：

```bash
sh install.sh
```

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


### 3、安装后的主要目录

```text
~/.soloncode/
+-- AGENTS.md              # 全局智能体提示词
+-- settings.json          # Web 设置页维护的全局配置
+-- bin/
|   +-- soloncode-cli.jar
|   +-- soloncode
|   +-- soloncode.ps1
|   +-- uninstall.sh
|   +-- uninstall.ps1
|   +-- uninstall.cmd
+-- skills/                # 全局 Skills
+-- agents/                # 全局子代理
+-- commands/              # 全局自定义命令
+-- extensions/            # Java 扩展插件
+-- memory/                # 全局长期记忆（默认）
```


### 4、首次配置模型（使用 Web 设置页）

```bash
soloncode web
```

打开浏览器后进入“设置 -> 大语言模型”，添加模型并测试连接。推荐新用户优先使用这种方式。

使用固定端口时，如果该端口已经运行 SolonCode，命令会复用已有实例，并把当前启动目录打开为一个工作区：

```bash
soloncode web
```

如果固定端口被其他服务占用，命令会明确报错。


### 5、如何卸载

Mac / Linux：

```bash
sh ~/.soloncode/bin/uninstall.sh
```

Windows (PowerShell)：

```powershell
& "$HOME/.soloncode/bin/uninstall.ps1"
```
