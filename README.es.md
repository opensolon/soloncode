<div align="center">
<h1>SolonCode</h1>
<p>Un agente de codificación de código abierto construido con <a href="https://github.com/opensolon/solon-ai">Solon AI</a> y Java (compatible con entornos de ejecución Java8 a Java26)</p>
<p>Última versión: v2026.5.4</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Instalación y configuración

Instalación:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configuración (debe modificarse después de la instalación):

* Directorio de instalación: `~/soloncode/bin/`
* Localice el archivo de configuración `~/soloncode/config.yml` y modifique la configuración de `models` (principalmente)
* Para las opciones de configuración de `models`, consulte: [Configuración del modelo y opciones de solicitud](https://solon.noear.org/article/1087)

## Ejecución

Ejecute el comando `soloncode` desde cualquier directorio en la consola (es decir, su espacio de trabajo).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.4
/Users/noear
Tips: (esc) interrupt | /(tab) ls command | @(tab) ls agent

User
> 
```

Prueba de funcionalidades (intente las siguientes tareas, de simple a compleja):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Se recomienda instalar algunas habilidades previamente
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Documentación

Para más detalles de configuración, visite nuestra [Documentación oficial](https://solon.noear.org/article/soloncode).

## Contribuir

Si está interesado en contribuir con código, lea la [Documentación de contribución](https://solon.noear.org/article/623) antes de enviar un PR.

## Desarrollo basado en SolonCode

Si utiliza "soloncode" en el nombre de su proyecto (por ejemplo, "soloncode-dashboard" o "soloncode-app"), indique en el README que el proyecto no está desarrollado oficialmente por el equipo de OpenSolon y no tiene afiliación.

## Preguntas frecuentes: ¿Cuál es la diferencia con Claude Code?

Son funcionalmente similares, con las siguientes diferencias clave:

* Desarrollado con Java, 100% de código abierto.
* Completamente impulsado y construido con prompts en chino
* Agnóstico del proveedor. Configurar modelos según sea necesario. La iteración de modelos reducirá brechas y costos, haciendo importante la configuración flexible.
* Soporta simultáneamente la interfaz de línea de comandos (CLI), la interfaz del navegador (WEB) y la interfaz IDE de escritorio (Desktop).
* Compatible con Web, protocolo ACP para comunicación remota.