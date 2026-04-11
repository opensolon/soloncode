<div align="center">
<h1>SolonCode</h1>
<p>Um agente de codificação de código aberto construído com <a href="https://github.com/opensolon/solon-ai">Solon AI</a> e Java (suporta ambientes de runtime Java8 a Java26)</p>
<p>Versão Mais Recente: v2026.4.11</p>
<img width="600" src="SHOW.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Instalação e Configuração

Instalação:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configuração (deve ser modificada após a instalação):

* Diretório de instalação: `~/soloncode/bin/`
* Localize o arquivo de configuração `~/soloncode/config.yml` e modifique a configuração do `models` (principalmente)
* Para opções de configuração do `models`, consulte: [Configuração de Modelo e Opções de Requisição](https://solon.noear.org/article/1087)

## Execução

Execute o comando `soloncode` em qualquer diretório no console (ou seja, seu espaço de trabalho).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.11
/Users/noear
Tips: (esc) interrupt | '/exit': quit | '/resume': resume | '/clear': reset

User
> 
```

Teste de Funcionalidades (experimente as seguintes tarefas, do simples ao complexo):

* `olá`
* `use a web para analisar o protocolo ai mcp e depois gere um ppt` // Recomenda-se instalar algumas habilidades previamente
* `ajude-me a projetar uma equipe de agentes (salvar o design em demo-dis.md), para desenvolver um sistema clássico de gerenciamento de permissões com solon + java17 (demo-web), usando vue3 no frontend, com interface limpa e bonita`


## Documentação

Para mais detalhes de configuração, visite nossa [Documentação Oficial](https://solon.noear.org/article/soloncode).

## Contribuir

Se você tem interesse em contribuir com código, leia a [Documentação de Contribuição](https://solon.noear.org/article/623) antes de enviar um PR.

## Desenvolvimento Baseado no SolonCode

Se você usar "soloncode" no nome do seu projeto (por exemplo, "soloncode-dashboard" ou "soloncode-app"), indique no README que o projeto não é desenvolvido oficialmente pela equipe OpenSolon e não possui afiliação.

## Perguntas Frequentes

Eles são funcionalmente semelhantes, com diferenças principais:

* Construído com Java, 100% código aberto.
* Totalmente orientado e construído com prompts em chinês
* Independente de provedor. Requer configuração de modelo. A iteração de modelos reduzirá lacunas e custos, tornando a abordagem independente de provedor importante.
* Focado na interface de linha de comando (CLI) de terminal, executando via comandos do sistema.
* Suporta Web, protocolo ACP para comunicação remota.