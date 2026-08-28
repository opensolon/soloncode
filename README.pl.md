<div align="center">
<h1>SolonCode</h1>
<p>Otwartoźródłowy agent kodowania zbudowany na bazie <a href="https://github.com/opensolon/solon-ai">Solon AI</a> i Javy (obsługuje środowiska uruchomieniowe Java8 do Java26)</p>
<p>Najnowsza wersja: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="Obszar roboczy SolonCode Desktop" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Instalacja i konfiguracja

Instalacja:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Konfiguracja (nowym użytkownikom zaleca się najpierw skonfigurować przez stronę ustawień Web):

```
soloncode web 0
```

Po wejściu na stronę otwórz "Ustawienia -> Duży model językowy (LLM)", dodaj model i przetestuj połączenie.

<img height="260" src="SETTINGS-LLM.png">

## Uruchamianie

Uruchom polecenie `soloncode cli` (CLI interaktywne) lub `soloncode web 0` (Web interaktywne) z dowolnego katalogu w konsoli (czyli w swoim obszarze roboczym).

* `soloncode cli` (CLI interaktywne)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web interaktywne)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Testowanie funkcji (wypróbuj następujące zadania, od prostych do złożonych):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Zaleca się wcześniejsze zainstalowanie niektórych umiejętności
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop to lokalne środowisko IDE projektu SolonCode. Łączy rozmowy z Agentem, pliki projektu, edytor Monaco, zintegrowany terminal, zmiany Git i wykonywanie zadań w jednym obszarze roboczym. Klient korzysta z Tauri, React i TypeScript, a backend CLI w Javie zapewnia środowisko Agentów, dostęp do modeli i narzędzia.

Najważniejsze funkcje:

* **Tryby Agenta** — wykonywanie z zatwierdzeniem, automatyczna edycja, planowanie tylko do odczytu i ciągłe wykonywanie Goal.
* **Rozmowy powiązane z projektem** — obrazy i pliki, kontekst workspace, dodawanie zadań w trakcie pracy oraz statystyki modelu, Tokenów i czasu.
* **Niezawodne sesje** — trwała historia, pamięć długoterminowa, cofanie, ponawianie, bezpieczne usuwanie i punkty kontrolne.
* **Zintegrowane narzędzia programistyczne** — pliki, edytor, terminal, Git, lista zadań, Skills, Agents, MCP, OpenAPI, LSP i automatyzacje.

Przy uruchamianiu ze źródeł backend należy uruchomić osobno:

~~~bash
# Terminal 1: backend Desktop
soloncode serve 4808

# Terminal 2: klient Desktop
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

Tryb deweloperski łączy się z portem 4808 i nie uruchamia ani nie wykrywa automatycznie procesu backend. Więcej informacji zawiera [README Desktop](soloncode-desktop/README.md) i [przewodnik wprowadzający SolonCode Desk](docs/soloncode-desk-getting-started.md).

## Dokumentacja

Aby uzyskać więcej szczegółów dotyczących konfiguracji, odwiedź naszą [Oficjalną dokumentację](https://solon.noear.org/article/soloncode).

## Wkład

Jeśli jesteś zainteresowany wniesieniem wkładu w kod, przeczytaj [Dokumentację wkładu](https://solon.noear.org/article/623) przed przesłaniem PR.

## Tworzenie na bazie SolonCode

Jeśli używasz "soloncode" w nazwie swojego projektu (np. "soloncode-dashboard" lub "soloncode-app"), wskaż w pliku README, że projekt nie jest oficjalnie rozwijany przez zespół OpenSolon i nie jest z nim powiązany.

## Często zadawane pytania: Czym różni się od Claude Code?

Pod względem funkcjonalności są podobne, z kluczowymi różnicami:

* Zbudowany w Javie, w 100% otwarty kod źródłowy. Kompatybilny z BiSheng JDK (Huawei) i Harmony PC.
* W pełni sterowany i budowany przy użyciu promptów w języku chińskim
* Niezależny od dostawcy. Konfiguruj modele według potrzeb. Iteracja modeli będzie zmniejszać luki i obniżać koszty, co sprawia, że elastyczna konfiguracja jest ważna.
* Jednocześnie obsługuje interfejs wiersza poleceń terminala (CLI), interfejs przeglądarki (WEB) i interfejs IDE na pulpicie (Desktop).
* Obsługuje Web, protokół ACP do komunikacji zdalnej.
