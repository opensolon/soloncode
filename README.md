<div align="center">
<h1>SolonCode</h1>
<p>An open-source coding agent built with <a href="https://github.com/opensolon/solon-ai">Solon AI</a> and Java (supports Java8 to Java26 runtime environments)</p>
<p>Latest Version: v2026.5.4</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>


## Installation and Configuration

Installation:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configuration (must be modified after installation):

* Installation directory: `~/soloncode/bin/`
* Locate the `~/soloncode/config.yml` configuration file and modify the `models` configuration (primarily)
* For `models` configuration options, refer to: [Model Configuration and Request Options](https://solon.noear.org/article/1087)

## Running

Run the `soloncode` command from any directory in the console (i.e., your workspace).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.4
/Users/noear
Tips: (esc) interrupt | /(tab) ls command | @(tab) ls agent

User
> 
```

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

* Built with Java, 100% open-source.
* Pure Chinese prompt-driven development and construction.
* Provider-agnostic. Configure models as needed. Model iteration will narrow gaps and reduce costs, making flexible configuration important.
* Simultaneously supports terminal command-line interface (CLI), browser interface (WEB), and desktop IDE interface (Desktop).
* Supports Web, ACP protocol for remote communication.