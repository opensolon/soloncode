<div align="center">
<h1>SolonCode</h1>
<p>SolonCode è un agente di codifica open source basato su <a href="https://github.com/opensolon/solon-ai">Solon AI</a> e Java, che supporta ambienti runtime da Java8 a Java26.</p>
<p>Ultima Versione: v2026.5.5</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Installazione e configurazione

Installazione:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configurazione (da modificare dopo l'installazione):

* Directory di installazione: `~/soloncode/bin/`
* Individuare il file di configurazione `~/soloncode/config.yml` e modificare la configurazione `models` (principalmente)
* Per le opzioni di configurazione di `models`, consultare: [Configurazione del Modello e Opzioni di Richiesta](https://solon.noear.org/article/1087)

## Esecuzione

Eseguire il comando `soloncode` da qualsiasi directory nella console (ovvero, la vostra area di lavoro).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.5
/Users/noear
Tips: (esc) interrupt | /(tab) ls command | @(tab) ls agent

User
> 
```

Test delle Funzionalità (provare i seguenti task, dal semplice al complesso):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Si consiglia di installare alcune skill in anticipo
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Documentazione

Per maggiori dettagli sulla configurazione, visitare la [Documentazione Ufficiale](https://solon.noear.org/article/soloncode).

## Contribuire

Se siete interessati a contribuire al codice, leggete la [Documentazione per i Contributi](https://solon.noear.org/article/623) prima di inviare una PR.

## Sviluppo Basato su SolonCode

Se utilizzate "soloncode" nel nome del vostro progetto (ad esempio, "soloncode-dashboard" o "soloncode-app"), indicate nel README che il progetto non è sviluppato ufficialmente dal team OpenSolon e non ha alcuna affiliazione.

## Domande frequenti: Qual è la differenza rispetto a Claude Code?

Sono funzionalmente simili, con differenze chiave:

* Sviluppato in Java, 100% open-source.
* Interamente guidato e costruito con prompt in cinese
* Agnostico rispetto ai provider. Configurare i modelli secondo le necessità. L'iterazione dei modelli ridurrà i divari e i costi, rendendo importante la configurazione flessibile.
* Supporta contemporaneamente l'interfaccia a riga di comando (CLI), l'interfaccia browser (WEB) e l'interfaccia IDE desktop (Desktop).
* Supporta Web, protocollo ACP per la comunicazione remota.