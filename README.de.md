<div align="center">
<h1>SolonCode</h1>
<p>Ein Open-Source-Coding-Agent, der mit <a href="https://github.com/opensolon/solon-ai">Solon AI</a> und Java entwickelt wurde (unterstützt Java8 bis Java26 Laufzeitumgebungen)</p>
<p>Aktuelle Version: v2026.4.22</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Installation und Konfiguration

Installation:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Konfiguration (muss nach der Installation angepasst werden):

* Installationsverzeichnis: `~/soloncode/bin/`
* Suchen Sie die Konfigurationsdatei `~/soloncode/config.yml` und passen Sie die `models`-Konfiguration an (in erster Linie)
* Für `models`-Konfigurationsoptionen siehe: [Modellkonfiguration und Anfrageoptionen](https://solon.noear.org/article/1087)

## Ausführung

Führen Sie den Befehl `soloncode` in einem beliebigen Verzeichnis in der Konsole aus (d.h. Ihr Arbeitsverzeichnis).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.22
/Users/noear
Tips: (esc) interrupt | '/exit': quit | '/resume': resume | '/clear': reset

User
> 
```

Funktionstest (probieren Sie die folgenden Aufgaben, von einfach bis komplex):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Es wird empfohlen, einige Skills im Voraus zu installieren
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Dokumentation

Weitere Konfigurationsdetails finden Sie in unserer [Offiziellen Dokumentation](https://solon.noear.org/article/soloncode).

## Mitwirken

Wenn Sie an der Mitwirkung am Code interessiert sind, lesen Sie bitte die [Mitwirkungs-Dokumentation](https://solon.noear.org/article/623), bevor Sie einen PR einreichen.

## Entwicklung auf Basis von SolonCode

Wenn Sie "soloncode" in Ihrem Projektnamen verwenden (z.B. "soloncode-dashboard" oder "soloncode-app"), geben Sie bitte in der README an, dass das Projekt nicht vom OpenSolon-Team offiziell entwickelt wurde und keine Verbindung dazu besteht.

## Häufig gestellte Fragen: Was ist der Unterschied zu Claude Code und OpenCode?

Sie sind funktionell ähnlich, mit folgenden wesentlichen Unterschieden:

* Mit Java entwickelt, 100% Open-Source.
* Rein chinesisch Prompt-gesteuert und gebaut
* Anbieterunabhängig. Erfordert Modellkonfiguration. Die Modelliteration wird Lücken schließen und Kosten senken, was den anbieterunabhängigen Ansatz wichtig macht.
* Fokussiert auf Terminal-Kommandozeilenschnittstelle (CLI), läuft über Systembefehle.
* Unterstützt Web, ACP-Protokoll zur Fernkommunikation.