<div align="center">
<h1>SolonCode</h1>
<p>An open-source coding agent built with <a href="https://github.com/opensolon/solon-ai">Solon AI</a> and Java (supports Java8 to Java26 runtime environments)</p>
<p>Latest Version: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="SolonCode Desktop workspace" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>


## Installation and Configuration

Installation:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configuration (new users are recommended to configure via the Web settings page first):

```
soloncode web 0
```

Once on the page, open "Settings -> LLM", add a model, and test the connection.

<img height="260" src="SETTINGS-LLM.png">

## Running

Run the `soloncode cli` (CLI interactive), `soloncode web 0` (Web interactive), or SolonCode Desktop from your project workspace.

* `soloncode` (CLI interactive)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web interactive)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/Users/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

## SolonCode Desktop

SolonCode Desktop is the local IDE experience for SolonCode. It brings Agent conversations, project files, a Monaco-based editor, an integrated terminal, Git changes, and task execution into one workspace. The desktop client is built with Tauri, React, and TypeScript, while the Java CLI backend provides the Agent runtime, model access, and tools.

Desktop highlights:

* **Agent work modes** — approval execution, automatic editing, read-only planning, and persistent Goal execution.
* **Project-aware conversations** — attach images and files, reference workspace context, queue follow-up tasks while an Agent is running, and keep model/Token/time statistics with each response.
* **Reliable sessions** — persistent history, long-term memory, rewind, redo, safe deletion, and recoverable workspace checkpoints.
* **Integrated development tools** — file explorer, Monaco editor, terminal, Git workflow, task list, and change review.
* **Extensible runtime** — Skills, Agents, MCP, OpenAPI, LSP, automations, and channel binding.
* **Customizable workspace** — multiple model providers, light/dark themes, and project-scoped settings.

To run the desktop client from source, start its backend separately and then launch Tauri:

```bash
# Terminal 1: desktop backend
soloncode serve 4808

# Terminal 2: desktop client
cd soloncode-desktop
npm install
npm run tauri:dev
```

Development mode connects to the backend on port `4808`; it does not start or probe the backend process automatically. For more details, see the [Desktop README](soloncode-desktop/README.md) and the [SolonCode Desk getting-started guide](docs/soloncode-desk-getting-started.md).

Feature Testing (try the following tasks, from simple to complex):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // It's recommended to install some skills in advance
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Documentation

For more configuration details, please visit our [Official Documentation](https://solon.noear.org/article/soloncode).

## Contributing

If you're interested in contributing code, please read the [Contributing Docs](https://solon.noear.org/article/623) before submitting a PR.

## Developing Based on SolonCode

If you use "soloncode" in your project name (e.g., "soloncode-dashboard" or "soloncode-app"), please indicate in the README that the project is not officially developed by the OpenSolon team and has no affiliation.

## FAQ: What's the difference from Claude Code?

They are functionally similar, with key differences:

* Built with Java, 100% open-source. Compatible with BiSheng JDK (Huawei) and Harmony PC.
* Pure Chinese prompt-driven development and construction.
* Provider-agnostic. Configure models as needed. Model iteration will narrow gaps and reduce costs, making flexible configuration important.
* Simultaneously supports terminal command-line interface (CLI), browser interface (WEB), and desktop IDE interface (Desktop).
* Supports Web, ACP protocol for remote communication.
