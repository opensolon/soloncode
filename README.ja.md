<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a>とJavaで構築されたオープンソースのコーディングエージェント（Java8からJava26のランタイム環境をサポート）</p>
<p>最新バージョン: v2026.4.28</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## インストールと設定

インストール方法：

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

設定（インストール後に必ず変更してください）：

* インストールディレクトリ: `~/soloncode/bin/`
* `~/soloncode/config.yml` 設定ファイルを開き、`models` 設定を変更してください（主な設定項目）
* `models` の設定オプションについては、[モデル設定とリクエストオプション](https://solon.noear.org/article/1087)を参照してください

## 実行

コンソールの任意のディレクトリ（ワークスペース）で `soloncode` コマンドを実行してください。

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.28
/Users/noear
Tips: (esc) interrupt | '/exit' | '/resume' | '/clear' | '/model'

User
> 
```

機能テスト（以下のタスクをお試しください、簡単なものから複雑なものへ）：

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // 事前にいくつかのスキルをインストールすることをお勧めします
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## ドキュメント

詳細な設定については、[公式ドキュメント](https://solon.noear.org/article/soloncode)をご覧ください。

## 貢献

コードの貢献にご興味がある方は、PRを提出する前に[貢献ガイド](https://solon.noear.org/article/623)をお読みください。

## SolonCodeをベースにした開発

プロジェクト名に「soloncode」を使用する場合（例：「soloncode-dashboard」や「soloncode-app」）、READMEに当該プロジェクトがOpenSolonチームによって公式に開発されたものではなく、関連性がないことを明記してください。

## よくある質問: Claude CodeやOpenCodeとの違いは？

機能的には類似していますが、主な違いは以下の通りです：

* Javaで構築されており、100%オープンソースです
* 純中国語プロンプトで駆動・構築
* プロバイダーに依存しません。モデルの設定が必要です。モデルの進化によりギャップが縮まり、コストが削減されるため、プロバイダー非依存のアプローチが重要になります
* ターミナルのコマンドラインインターフェース（CLI）に重点を置いており、システムコマンド経由で実行されます
* Web、ACPプロトコルをサポートし、リモート通信が可能です