<div align="center">
<h1>SolonCode</h1>
<p>SolonCode è un agente di codifica open source basato su <a href="https://github.com/opensolon/solon-ai">Solon AI</a> e Java, che supporta ambienti runtime da Java8 a Java26.</p>
<p>Ultima Versione: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="Area di lavoro SolonCode Desktop" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Installazione e configurazione

Installazione:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configurazione (si consiglia ai nuovi utenti di configurare prima tramite la pagina delle impostazioni Web):

```
soloncode web 0
```

Una volta entrati nella pagina, aprire "Impostazioni -> Modello Linguistico di Grandi Dimensioni (LLM)", aggiungere un modello e testare la connessione.

<img height="260" src="SETTINGS-LLM.png">

## Esecuzione

Eseguire il comando `soloncode cli` (CLI interattiva) o `soloncode web 0` (Web interattiva) da qualsiasi directory nella console (ovvero, la vostra area di lavoro).

* `soloncode cli` (CLI interattiva)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web interattiva)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Test delle Funzionalità (provare i seguenti task, dal semplice al complesso):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Si consiglia di installare alcune skill in anticipo
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop è l’esperienza IDE locale di SolonCode. Riunisce conversazioni con gli Agent, file di progetto, editor Monaco, terminale integrato, modifiche Git ed esecuzione delle attività in un unico spazio di lavoro. Il client è realizzato con Tauri, React e TypeScript, mentre il backend CLI Java fornisce il runtime degli Agent, l’accesso ai modelli e gli strumenti.

Funzionalità principali:

* **Modalità Agent** — esecuzione con approvazione, modifica automatica, pianificazione in sola lettura ed esecuzione Goal continua.
* **Conversazioni legate al progetto** — allegati immagine e file, contesto del workspace, attività aggiuntive durante l’esecuzione e statistiche su modello, Token e durata.
* **Sessioni affidabili** — cronologia persistente, memoria a lungo termine, rollback, riesecuzione, eliminazione sicura e checkpoint del workspace.
* **Strumenti di sviluppo integrati** — file, editor, terminale, Git, elenco attività, Skills, Agents, MCP, OpenAPI, LSP e automazioni.

Per eseguire il client dai sorgenti, avvia il backend separatamente:

~~~bash
# Terminale 1: backend desktop
soloncode serve 4808

# Terminale 2: client desktop
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

La modalità di sviluppo si collega alla porta 4808 e non avvia né rileva automaticamente il processo backend. Consulta il [README Desktop](soloncode-desktop/README.md) e la [guida introduttiva di SolonCode Desk](docs/soloncode-desk-getting-started.md).

## Documentazione

Per maggiori dettagli sulla configurazione, visitare la [Documentazione Ufficiale](https://solon.noear.org/article/soloncode).

## Contribuire

Se siete interessati a contribuire al codice, leggete la [Documentazione per i Contributi](https://solon.noear.org/article/623) prima di inviare una PR.

## Sviluppo Basato su SolonCode

Se utilizzate "soloncode" nel nome del vostro progetto (ad esempio, "soloncode-dashboard" o "soloncode-app"), indicate nel README che il progetto non è sviluppato ufficialmente dal team OpenSolon e non ha alcuna affiliazione.

## Domande frequenti: Qual è la differenza rispetto a Claude Code?

Sono funzionalmente simili, con differenze chiave:

* Sviluppato in Java, 100% open-source. Compatibile con BiSheng JDK (Huawei) e Harmony PC.
* Interamente guidato e costruito con prompt in cinese
* Agnostico rispetto ai provider. Configurare i modelli secondo le necessità. L'iterazione dei modelli ridurrà i divari e i costi, rendendo importante la configurazione flessibile.
* Supporta contemporaneamente l'interfaccia a riga di comando (CLI), l'interfaccia browser (WEB) e l'interfaccia IDE desktop (Desktop).
* Supporta Web, protocollo ACP per la comunicazione remota.
