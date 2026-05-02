<div align="center">
<h1>SolonCode</h1>
<p>SolonCode je open-source kodirajući agent izgrađen s <a href="https://github.com/opensolon/solon-ai">Solon AI</a> i Javom (podržava Java8 do Java26 runtime okruženja)</p>
<p>Najnovija verzija: v2026.5.2</p>
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
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Konfiguracija (mora se modificirati nakon instalacije):

* Instalacijski direktorij: `~/soloncode/bin/`
* Pronađite konfiguracijsku datoteku `~/soloncode/config.yml` i modificirajte `models` konfiguraciju (primarno)
* Za `models` konfiguracijske opcije, pogledajte: [Konfiguracija modela i opcije zahtjeva](https://solon.noear.org/article/1087)

## Pokretanje

Pokrenite naredbu `soloncode` iz bilo kojeg direktorija u konzoli (tj. vaš radni prostor).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.2
/Users/noear
Tips: (esc) interrupt | '/exit' | '/resume' | '/clear' | '/model'

User
> 
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

## Često postavljana pitanja: Koja je razlika od Claude Code i OpenCode?

Oni su funkcionalno slični, sa ključnim razlikama:

* Izgrađen s Javom, 100% open-source.
* Nezavisan od pružatelja usluga. Zahtijeva konfiguraciju modela. Iteracija modela će smanjiti razlike i troškove, čineći pristup nezavisnim od pružatelja usluga važnim.
* Fokusiran na terminalski komandni interfejs (CLI), pokretanje putem sistemskih komandi.
* Podržava Web, ACP protokol za udaljenu komunikaciju.