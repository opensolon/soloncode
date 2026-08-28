<div align="center">
<h1>SolonCode</h1>
<p>基於 Solon AI 與 Java 實現的開源編碼智能體（支援 Java8 到 Java26 環境啟動）</p>
<p>最新版本：v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="SolonCode Desktop 工作台" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## 安裝與設定

安裝：

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

修改設定（新用戶推薦先用 Web 設定頁配置：）：

```
soloncode web 0
```

進入頁面後打開"設定 -> 大語言模型"，添加模型並測試連接。

<img height="260" src="SETTINGS-LLM.png">

## 執行

在控制台「任意」目錄（即工作區）下，執行 `soloncode cli`（CLI 互動）或者 `soloncode web 0`（Web 互動）命令即可。

* `soloncode cli`（CLI 互動）

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0`（Web 互動）

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

效果測試（分別嘗試以下任務，從簡單到複雜）：

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` //最好提前安装些 skill
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode 桌面版

SolonCode Desktop 是 SolonCode 的本機 IDE 形態，將 Agent 對話、專案檔案、Monaco 編輯器、終端機、Git 變更和任務執行集中在同一個工作區。桌面用戶端以 Tauri、React 和 TypeScript 建置，Java CLI 後端負責 Agent 執行環境、模型存取與工具呼叫。

桌面版特色：

* **Agent 工作模式** — 支援審批執行、自動編輯、唯讀規劃和持續 Goal 執行。
* **專案級對話** — 支援圖片與檔案附件、工作區上下文、執行中追加任務，以及每則回答的模型、Token 和耗時統計。
* **可靠工作階段** — 支援歷史持久化、長期記憶、回退、重做、安全刪除和工作區檢查點。
* **完整開發工作台** — 整合檔案管理、程式碼編輯器、終端機、Git、任務清單、Skills、Agents、MCP、OpenAPI、LSP 和自動化。

從原始碼執行桌面端時，需要另外啟動後端：

~~~bash
# 終端機 1：桌面後端
soloncode serve 4808

# 終端機 2：桌面用戶端
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

開發模式連線至 4808 連接埠，不會自動啟動或偵測後端程序。更多資訊請參閱 [Desktop README](soloncode-desktop/README.md) 和 [SolonCode Desk 入門指南](docs/soloncode-desk-getting-started.md)。

## 文檔

更多設定說明請查看我們的 官方文檔。

## 參與貢獻

如有興趣貢獻程式碼，請在提交 PR 前閱讀 貢獻指南。

## 基於 SolonCode 進行開發

如果您在專案名稱中使用了 "soloncode"，請在 README 裡註明該專案不是 OpenSolon 團隊官方開發。

## 常見問題：和 Claude Code 有什麼不同？

功能上很相似，關鍵差異：

* 採用 Java 實現，100% 開源。兼容畢昇 JDK（Huawei BiSheng JDK），兼容鴻蒙 PC（Huawei Harmony PC）。
* 純中文提示詞驅動與構建。
* 不綁定特定提供商。按需設定模型。模型迭代會縮小差異、降低成本，因此自由設定很重要。
* 同時支援終端命令列介面 (CLI)、瀏覽器介面（WEB）、桌面IDE介面（Desktop）。
* 支援 Web，ACP 協議進行遠端通訊。
