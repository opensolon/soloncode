<div align="center">
<h1>SolonCode</h1>
<p>基於 Solon AI 與 Java 實現的開源編碼智能體（支援 Java8 到 Java26 環境啟動）</p>
<p>最新版本：v2026.4.16</p>
<img width="600" src="SHOW.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## 安裝與設定

安裝：

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

修改設定（安裝後，必須先修改設定）：

* 安裝後的目錄：`~/soloncode/bin/`
* 找到 `~/soloncode/config.yml` 設定檔，（主要）修改 `models` 設定
* `models` 設定項，可參考：《模型設定與請求選項》

## 執行

在控制台「任意」目錄（即工作區）下，執行 `soloncode` 命令即可。

## 文檔

更多設定說明請查看我們的 官方文檔。

## 參與貢獻

如有興趣貢獻程式碼，請在提交 PR 前閱讀 貢獻指南。

## 基於 SolonCode 進行開發

如果您在專案名稱中使用了 "soloncode"，請在 README 裡註明該專案不是 OpenSolon 團隊官方開發。

## 常見問題：和 Claude Code、OpenCode 有什麼不同？

功能上很相似，關鍵差異：

* 採用 Java 實現，100% 開源。
* 不綁定特定提供商。需要設定模型。
* 聚焦終端命令列介面 (CLI)。
* 支援 Web，ACP 協議進行遠端通訊。