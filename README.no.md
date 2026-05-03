<div align="center">
<h1>SolonCode</h1>
<p>SolonCode er en åpen kildekode-kodingsagent basert på Solon AI og Java, som støtter kjøremiljøer fra Java8 til Java26.</p>
<p>Siste versjon: v2026.5.4</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Installasjon og konfigurasjon

Installasjon:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Konfigurasjon (må endres etter installasjon):

* Installasjonskatalog: `~/soloncode/bin/`
* Finn konfigurasjonsfilen `~/soloncode/config.yml` og endre `models`-konfigurasjonen (primært)
* For `models` konfigurasjonsalternativer, se: [Modellkonfigurasjon og forespørselsalternativer](https://solon.noear.org/article/1087)

## Kjøring

Kjør `soloncode`-kommandoen fra hvilken som helst katalog i konsollen (dvs. arbeidsområdet ditt).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.4
/Users/noear
Tips: (esc) interrupt | /(tab) ls command | @(tab) ls agent

User
> 
```

Funksjonstesting (prøv følgende oppgaver, fra enkel til kompleks):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Det anbefales å installere noen ferdigheter på forhånd
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Dokumentasjon

For flere konfigurasjonsdetaljer, vennligst besøk vår [Offisiell dokumentasjon](https://solon.noear.org/article/soloncode).

## Bidra

Hvis du er interessert i å bidra med kode, vennligst les [Bidragsdokumentasjon](https://solon.noear.org/article/623) før du sender inn en PR.

## Utvikling basert på SolonCode

Hvis du bruker "soloncode" i prosjektnavnet ditt (f.eks. "soloncode-dashboard" eller "soloncode-app"), vennligst indiker i README at prosjektet ikke er offisielt utviklet av OpenSolon-teamet og ikke har noen tilknytning.

## Ofte stilte spørsmål: Hva er forskjellen fra Claude Code og OpenCode?

De er funksjonelt like, med viktige forskjeller:

* Bygget med Java, 100% åpen kildekode.
* Helt drevet og bygget med kinesiske prompter
* Leverandøruavhengig. Krever modellkonfigurasjon. Modelliterasjon vil redusere gap og kostnader, noe som gjør leverandøruavhengig tilnærming viktig.
* Fokusert på terminal kommandolinjegrensesnitt (CLI), kjøres via systemkommandoer.
* Støtter Web, ACP-protokoll for ekstern kommunikasjon.