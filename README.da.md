<div align="center">
<h1>SolonCode</h1>
<p>En open-source kodningsagent bygget med <a href="https://github.com/opensolon/solon-ai">Solon AI</a> og Java (understøtter Java8 til Java26 runtime-miljøer)</p>
<p>Nyeste version: v2026.4.22</p>
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
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Konfiguration (skal ændres efter installation):

* Installationsmappe: `~/soloncode/bin/`
* Find konfigurationsfilen `~/soloncode/config.yml` og rediger `models`-konfigurationen (primært)
* For `models`-konfigurationsmuligheder, se: [Modelkonfiguration og anmodningsindstillinger](https://solon.noear.org/article/1087)

## Kørsel

Kør `soloncode`-kommandoen fra en hvilken som helst mappe i konsollen (dvs. dit arbejdsområde).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.22
/Users/noear
Tips: (esc) interrupt | '/exit': quit | '/resume': resume | '/clear': reset

User
> 
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

## Ofte stillede spørgsmål: Hvad er forskellen fra Claude Code og OpenCode?

De er funktionelt lignende, med følgende nøgleforskelle:

* Bygget med Java, 100% open-source.
* Udbyderuafhængig. Kræver modelkonfiguration. Modeliteration vil mindske forskelle og reducere omkostninger, hvilket gør en udbyderuafhængig tilgang vigtig.
* Fokuseret på terminal kommandolinje-interface (CLI), kører via systemkommandoer.
* Understøtter Web, ACP-protokol til fjernkommunikation.