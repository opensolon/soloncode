<div align="center">
<h1>SolonCode</h1>
<p>Un agent de codage open-source construit avec <a href="https://github.com/opensolon/solon-ai">Solon AI</a> et Java (prend en charge les environnements d'exécution Java8 à Java26)</p>
<p>Dernière version : v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="Espace de travail SolonCode Desktop" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Installation et configuration

Installation :

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell) :
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configuration (les nouveaux utilisateurs sont invités à configurer d'abord via la page des paramètres Web) :

```
soloncode web 0
```

Une fois sur la page, ouvrez "Paramètres -> LLM", ajoutez un modèle et testez la connexion.

<img height="260" src="SETTINGS-LLM.png">

## Exécution

Exécutez la commande `soloncode` (CLI interactif) ou `soloncode web 0` (Web interactif) depuis n'importe quel répertoire dans la console (c'est-à-dire votre espace de travail).

* `soloncode cli` (CLI interactif)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web interactif)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Test des fonctionnalités (essayez les tâches suivantes, de la plus simple à la plus complexe) :

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Il est recommandé d'installer certaines compétences au préalable
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop est l’expérience IDE locale de SolonCode. Les conversations avec l’Agent, les fichiers du projet, l’éditeur Monaco, le terminal intégré, les modifications Git et l’exécution des tâches sont réunis dans un seul espace de travail. Le client utilise Tauri, React et TypeScript, tandis que le backend CLI Java fournit l’exécution des Agents, l’accès aux modèles et les outils.

Fonctionnalités principales :

* **Modes Agent** — exécution avec approbation, édition automatique, planification en lecture seule et exécution Goal continue.
* **Conversations liées au projet** — images et fichiers joints, contexte du workspace, ajout de tâches pendant l’exécution et statistiques modèle/Token/durée.
* **Sessions fiables** — historique persistant, mémoire à long terme, retour en arrière, nouvelle exécution, suppression sûre et points de contrôle.
* **Outils de développement intégrés** — fichiers, éditeur, terminal, Git, liste de tâches, Skills, Agents, MCP, OpenAPI, LSP et automatisations.

Pour lancer le client depuis les sources, démarrez le backend séparément :

~~~bash
# Terminal 1 : backend desktop
soloncode serve 4808

# Terminal 2 : client desktop
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

Le mode développement se connecte au port 4808 et ne démarre ni ne détecte automatiquement le processus backend. Consultez le [README Desktop](soloncode-desktop/README.md) et le [guide de démarrage SolonCode Desk](docs/soloncode-desk-getting-started.md).

## Documentation

Pour plus de détails sur la configuration, veuillez consulter notre [Documentation officielle](https://solon.noear.org/article/soloncode).

## Contribuer

Si vous souhaitez contribuer au code, veuillez lire la [Documentation de contribution](https://solon.noear.org/article/623) avant de soumettre une PR.

## Développement basé sur SolonCode

Si vous utilisez « soloncode » dans le nom de votre projet (par exemple, « soloncode-dashboard » ou « soloncode-app »), veuillez indiquer dans le README que le projet n'est pas développé officiellement par l'équipe OpenSolon et n'a aucune affiliation.

## Questions fréquemment posées : Quelle est la différence avec Claude Code ?

Ils sont fonctionnellement similaires, avec des différences clés :

* Construit avec Java, 100% open-source. Compatible avec BiSheng JDK (Huawei) et Harmony PC.
* Entièrement piloté et construit par des prompts en chinois
* Indépendant du fournisseur. Configurer les modèles selon les besoins. L'itération des modèles réduira les écarts et les coûts, rendant la configuration flexible importante.
* Prend simultanément en charge l'interface en ligne de commande (CLI), l'interface navigateur (WEB) et l'interface IDE de bureau (Desktop).
* Prend en charge Web, le protocole ACP pour la communication à distance.
