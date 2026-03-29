
<div align="center">
<h1>SolonCode</h1>
<p>基于 <a href="../../../../opensolon/solon-ai">Solon AI</a> 与 Java 实现的开源编码智能体（支持 Java8 到 Java26 环境启动）</p>
<p>最新版本：v2026.4.1</p>
<img width="600" src="SHOW.png" />
</div>


## 安装与配置

安装：

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

安装后的目录：

* `~/soloncode/bin/`

配置修改（安装后，先修改配置）：

* 找到 `~/solnocode/bin/config.yml` 配置文件，（主要）修改 `chatModel` 配置。

## 运行

在控制台“任意”目录下，运行 `soloncode` 命令即可。

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.1
/Users/noear
Tips: (esc) interrupt | '/exit': quit | '/resume': resume | '/clear': reset

User
> 
```

效果测试（分别尝试以下任务，从简单到复杂）：

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` //最好提前安装些 skill
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## 文档

更多配置说明请查看我们的 [官方文档](https://solon.noear.org/article/soloncode)。

## 参与贡献

如有兴趣贡献代码，请在提交 PR 前阅读 [贡献指南 (Contributing Docs)](https://solon.noear.org/article/623)。

## 基于 SolonCode 进行开发

如果你在项目名中使用了 “soloncode”（如 “soloncode-dashboard” 或 “soloncode-app”），请在 README 里注明该项目不是 OpenSolon 团队官方开发，且不存在隶属关系。

## 常见问题：和 Claude Code、OpenCode 有什么不同？

功能上很相似，关键差异：

* 采用 Java 实现，100% 开源。
* 不绑定特定提供商。需要配置模型。模型迭代会缩小差异、降低成本，因此保持 provider-agnostic 很重要。
* 聚焦终端命令行界面 (CLI)，通过命令行运行。
* 支持 Web，ACP 协议进行远程通讯。