<div align="center">
<h1>SolonCode</h1>
<p>Un agent de codage open-source construit avec <a href="https://github.com/opensolon/solon-ai">Solon AI</a> et Java (prend en charge les environnements d'exécution Java8 à Java26)</p>
<p>Dernière version : v2026.7.13</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

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
SolonCode v2026.7.13 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web interactif)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.7.13 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Test des fonctionnalités (essayez les tâches suivantes, de la plus simple à la plus complexe) :

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Il est recommandé d'installer certaines compétences au préalable
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


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