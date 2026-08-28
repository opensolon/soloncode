<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a>とJavaで構築されたオープンソースのコーディングエージェント（Java8からJava26のランタイム環境をサポート）</p>
<p>最新バージョン: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="SolonCode Desktop ワークスペース" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## インストールと設定

インストール方法：

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

設定（新規ユーザーは最初にWeb設定ページを使用することをお勧めします）：

```
soloncode web 0
```

ページに入ったら「設定 -> 大規模言語モデル」を開き、モデルを追加して接続をテストしてください。

<img height="260" src="SETTINGS-LLM.png">

## 実行

コンソールの任意のディレクトリ（ワークスペース）で、`soloncode cli`（CLI対話）または `soloncode web 0`（Web対話）コマンドを実行してください。

* `soloncode cli`（CLI対話）

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0`（Web対話）

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

機能テスト（以下のタスクをお試しください、簡単なものから複雑なものへ）：

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // 事前にいくつかのスキルをインストールすることをお勧めします
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop は、SolonCode のローカル IDE 版です。Agent との対話、プロジェクトファイル、Monaco エディター、統合ターミナル、Git の変更、タスク実行を一つのワークスペースにまとめます。デスクトップクライアントは Tauri、React、TypeScript で構築され、Java CLI バックエンドが Agent ランタイム、モデル接続、ツール実行を担当します。

主な機能：

* **Agent 実行モード** — 承認付き実行、自動編集、読み取り専用プラン、継続的な Goal 実行。
* **プロジェクト対応の対話** — 画像・ファイル添付、ワークスペースコンテキスト、実行中の追加タスク、モデル・Token・所要時間の表示。
* **信頼性の高いセッション** — 履歴の永続化、長期メモリ、巻き戻し、再実行、安全な削除、ワークスペースチェックポイント。
* **統合開発環境** — ファイル管理、コードエディター、ターミナル、Git、タスクリスト、Skills、Agents、MCP、OpenAPI、LSP、自動化。

ソースから実行する場合は、バックエンドを別に起動します：

~~~bash
# ターミナル 1：デスクトップバックエンド
soloncode serve 4808

# ターミナル 2：デスクトップクライアント
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

開発モードはポート 4808 のバックエンドへ接続し、バックエンドプロセスを自動起動・検出しません。詳細は [Desktop README](soloncode-desktop/README.md) と [SolonCode Desk 入門ガイド](docs/soloncode-desk-getting-started.md) を参照してください。

## ドキュメント

詳細な設定については、[公式ドキュメント](https://solon.noear.org/article/soloncode)をご覧ください。

## 貢献

コードの貢献にご興味がある方は、PRを提出する前に[貢献ガイド](https://solon.noear.org/article/623)をお読みください。

## SolonCodeをベースにした開発

プロジェクト名に「soloncode」を使用する場合（例：「soloncode-dashboard」や「soloncode-app」）、READMEに当該プロジェクトがOpenSolonチームによって公式に開発されたものではなく、関連性がないことを明記してください。

## よくある質問: Claude Codeとの違いは？

機能的には類似していますが、主な違いは以下の通りです：

* Javaで構築されており、100%オープンソースです。BiSheng JDK（Huawei）およびHarmony PCに対応しています。
* 純中国語プロンプトで駆動・構築
* プロバイダーに依存しません。必要に応じてモデルを設定できます。モデルの進化によりギャップが縮まり、コストが削減されるため、自由な設定が重要です
* ターミナルのコマンドラインインターフェース（CLI）、ブラウザインターフェース（WEB）、デスクトップIDEインターフェース（Desktop）を同時にサポートします
* Web、ACPプロトコルをサポートし、リモート通信が可能です
