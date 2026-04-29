<div align="center">
<h1>SolonCode</h1>
<p>Un agent de codage open-source construit avec <a href="https://github.com/opensolon/solon-ai">Solon AI</a> et Java (prend en charge les environnements d'exécution Java8 à Java26)</p>
<p>Dernière version : v2026.4.30</p>
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
# Mac / Linux :
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell) :
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configuration (doit être modifiée après l'installation) :

* Répertoire d'installation : `~/soloncode/bin/`
* Localisez le fichier de configuration `~/soloncode/config.yml` et modifiez la configuration `models` (principalement)
* Pour les options de configuration `models`, consultez : [Configuration du modèle et options de requête](https://solon.noear.org/article/1087)

## Exécution

Exécutez la commande `soloncode` depuis n'importe quel répertoire dans la console (c'est-à-dire votre espace de travail).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.30
/Users/noear
Tips: (esc) interrupt | '/exit' | '/resume' | '/clear' | '/model'

User
> 
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

## Questions fréquemment posées : Quelle est la différence avec Claude Code et OpenCode ?

Ils sont fonctionnellement similaires, avec des différences clés :

* Construit avec Java, 100% open-source.
* Entièrement piloté et construit par des prompts en chinois
* Indépendant du fournisseur. Nécessite une configuration du modèle. L'itération des modèles réduira les écarts et les coûts, rendant l'approche indépendante du fournisseur importante.
* Concentré sur l'interface en ligne de commande (CLI) du terminal, s'exécutant via les commandes système.
* Prend en charge Web, le protocole ACP pour la communication à distance.