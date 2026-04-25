<div align="center">
<h1>SolonCode</h1>
<p>Открытый исходный код интеллектуального агента для программирования, построенный на <a href="https://github.com/opensolon/solon-ai">Solon AI</a> и Java (поддерживает среды выполнения Java8 до Java26)</p>
<p>Последняя версия: v2026.4.28</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Установка и настройка

Установка:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Настройка (обязательно изменить после установки):

* Каталог установки: `~/soloncode/bin/`
* Найдите файл конфигурации `~/soloncode/config.yml` и измените конфигурацию `models` (в первую очередь)
* Для параметров конфигурации `models` обратитесь к: [Конфигурация модели и параметры запроса](https://solon.noear.org/article/1087)

## Запуск

Выполните команду `soloncode` из любой директории в консоли (то есть вашей рабочей области).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.28
/Users/noear
Tips: (esc) interrupt | '/exit' | '/resume' | '/clear' | '/model'

User
> 
```

Тестирование функций (попробуйте следующие задачи, от простых к сложным):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Рекомендуется предварительно установить некоторые навыки
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Документация

Для получения дополнительной информации о конфигурации посетите нашу [Официальную документацию](https://solon.noear.org/article/soloncode).

## Участие в разработке

Если вы хотите внести вклад в код, пожалуйста, прочитайте [Документацию для участников](https://solon.noear.org/article/623) перед отправкой PR.

## Разработка на основе SolonCode

Если вы используете "soloncode" в названии вашего проекта (например, "soloncode-dashboard" или "soloncode-app"), укажите в README, что проект не разрабатывается официально командой OpenSolon и не имеет к ней отношения.

## Часто задаваемые вопросы

Чем отличается от Claude Code и OpenCode?

Они функционально похожи, с ключевыми отличиями:

* Построен на Java, 100% с открытым исходным кодом.
* Полностью управляется и создаётся на основе промптов на китайском языке
* Независим от провайдеров. Требует конфигурации модели. Итерации моделей будут сокращать разрыв и снижать затраты, что делает подход независимости от провайдеров важным.
* Ориентирован на интерфейс командной строки терминала (CLI), запускается через системные команды.
* Поддерживает Web, протокол ACP для удалённого взаимодействия.