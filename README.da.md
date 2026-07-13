<div align="center">
<h1>SolonCode</h1>
<p>En open-source kodningsagent bygget med <a href="https://github.com/opensolon/solon-ai">Solon AI</a> og Java (understøtter Java8 til Java26 runtime-miljøer)</p>
<p>Nyeste version: v2026.7.13</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Installation og konfiguration

Installation:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Konfiguration (nye brugere anbefales at konfigurere via Web-indstillingssiden først):

```bash
soloncode web 0
```

Når du er på siden, skal du åbne "Indstillinger -> LLM", tilføje en model og teste forbindelsen.

<img height="260" src="SETTINGS-LLM.png">

## Kørsel

Kør kommandoen `soloncode cli` (CLI-interaktiv) eller `soloncode web 0` (Web-interaktiv) fra en hvilken som helst mappe i konsollen (dvs. dit arbejdsområde).

* `soloncode` (CLI-interaktiv)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.7.13 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web-interaktiv)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.7.13 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Funktionstest (prøv følgende opgaver, fra enkel til kompleks):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Det anbefales at installere nogle færdigheder på forhånd
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Dokumentation

For flere konfigurationsdetaljer, besøg venligst vores [Officiel Dokumentation](https://solon.noear.org/article/soloncode).

## Bidrag

Hvis du er interesseret i at bidrage med kode, bedes du læse [Bidragsdokumentationen](https://solon.noear.org/article/623) før du indsender en PR.

## Udvikling baseret på SolonCode

Hvis du bruger "soloncode" i dit projektnavn (f.eks. "soloncode-dashboard" eller "soloncode-app"), bedes du angive i README, at projektet ikke er officielt udviklet af OpenSolon-teamet og ikke har nogen tilknytning hertil.

## Ofte stillede spørgsmål: Hvad er forskellen fra Claude Code?

De er funktionelt lignende, med følgende nøgleforskelle:

* Bygget med Java, 100% open-source. Kompatibel med BiSheng JDK (Huawei) og Harmony PC.
* Helt drevet og bygget med kinesiske prompter
* Udbyderuafhængig. Konfigurer modeller efter behov. Modeliteration vil mindske forskelle og reducere omkostninger, hvilket gør fleksibel konfiguration vigtig.
* Understøtter samtidig terminal kommandolinje-interface (CLI), browser-interface (WEB) og desktop IDE-interface (Desktop).
* Understøtter Web, ACP-protokol til fjernkommunikation.