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
soloncode web 0
```

打开浏览器后进入“设置 -> 大语言模型”，添加模型并测试连接。推荐新用户优先使用这种方式。


### 5、如何卸载

Mac / Linux：

```bash
sh ~/.soloncode/bin/uninstall.sh
```

Windows (PowerShell)：

```powershell
& "$HOME/.soloncode/bin/uninstall.ps1"
```
