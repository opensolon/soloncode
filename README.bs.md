<div align="center">
<h1>SolonCode</h1>
<p>SolonCode je open-source kodirajući agent izgrađen s <a href="https://github.com/opensolon/solon-ai">Solon AI</a> i Javom (podržava Java8 do Java26 runtime okruženja)</p>
<p>Najnovija verzija: v2026.7.13</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Instalacija i konfiguracija

Instalacija:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Konfiguracija (novim korisnicima se preporučuje da prvo konfigurišu putem Web stranice za postavke):

```bash
soloncode web 0
```

Nakon ulaska na stranicu, otvorite "Postavke -> Veliki jezički model (LLM)", dodajte model i testirajte vezu.

<img height="260" src="SETTINGS-LLM.png">

## Pokretanje

Pokrenite naredbu `soloncode cli` (CLI interaktivno) ili `soloncode web 0` (Web interaktivno) iz bilo kojeg direktorija u konzoli (tj. vaš radni prostor).

* `soloncode` (CLI interaktivno)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.7.13 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web interaktivno)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.7.13 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Testiranje funkcija (isprobajte sljedeće zadatke, od jednostavnih do složenih):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Preporučuje se prethodna instalacija nekih vještina
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Dokumentacija

Za više detalja o konfiguraciji, posjetite našu [Službenu dokumentaciju](https://solon.noear.org/article/soloncode).

## Doprinos

Ako ste zainteresirani za doprinos kodom, molimo pročitajte [Dokumentaciju za doprinos](https://solon.noear.org/article/623) prije slanja PR-a.

## Razvoj zasnovan na SolonCode-u

Ako koristite "soloncode" u nazivu svog projekta (npr. "soloncode-dashboard" ili "soloncode-app"), molimo naznačite u README-u da projekt nije službeno razvijen od strane OpenSolon tima i nema službenu povezanost.

## Često postavljana pitanja: Koja je razlika od Claude Code?

Oni su funkcionalno slični, sa ključnim razlikama:

* Izgrađen s Javom, 100% open-source. Kompatibilan s BiSheng JDK (Huawei) i Harmony PC.
* Potpuno vođen i izgrađen kineskim promptovima
* Nezavisan od pružatelja usluga. Konfigurišite modele prema potrebi. Iteracija modela će smanjiti razlike i troškove, čineći fleksibilnu konfiguraciju važnom.
* Istovremeno podržava terminalski komandni interfejs (CLI), interfejs pretraživača (WEB) i desktop IDE interfejs (Desktop).
* Podržava Web, ACP protokol za udaljenu komunikaciju.