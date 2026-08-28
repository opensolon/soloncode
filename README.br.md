<div align="center">
<h1>SolonCode</h1>
<p>Um agente de codificação de código aberto construído com <a href="https://github.com/opensolon/solon-ai">Solon AI</a> e Java (suporta ambientes de runtime Java8 a Java26)</p>
<p>Versão Mais Recente: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="Área de trabalho do SolonCode Desktop" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Instalação e Configuração

Instalação:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Configuração (novos usuários são recomendados a configurar primeiro pela página de configurações Web):

```bash
soloncode web 0
```

Após entrar na página, abra "Configurações -> Modelo de Linguagem Grande (LLM)", adicione um modelo e teste a conexão.

<img height="260" src="SETTINGS-LLM.png">

## Execução

Execute o comando `soloncode cli` (CLI interativo) ou `soloncode web 0` (Web interativo) em qualquer diretório no console (ou seja, seu espaço de trabalho).

* `soloncode` (CLI interativo)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web interativo)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Teste de Funcionalidades (experimente as seguintes tarefas, do simples ao complexo):

* `olá`
* `use a web para analisar o protocolo ai mcp e depois gere um ppt` // Recomenda-se instalar algumas habilidades previamente
* `ajude-me a projetar uma equipe de agentes (salvar o design em demo-dis.md), para desenvolver um sistema clássico de gerenciamento de permissões com solon + java17 (demo-web), usando vue3 no frontend, com interface limpa e bonita`


## SolonCode Desktop

O SolonCode Desktop é a experiência de IDE local do SolonCode. Ele reúne conversas com Agents, arquivos do projeto, editor Monaco, terminal integrado, alterações do Git e execução de tarefas em um único espaço de trabalho. O cliente usa Tauri, React e TypeScript, enquanto o backend CLI em Java fornece o runtime dos Agents, o acesso aos modelos e as ferramentas.

Destaques:

* **Modos de Agent** — execução com aprovação, edição automática, planejamento somente leitura e execução contínua de Goal.
* **Conversas vinculadas ao projeto** — anexos de imagem e arquivo, contexto do workspace, tarefas adicionais durante a execução e estatísticas de modelo, Token e tempo.
* **Sessões confiáveis** — histórico persistente, memória de longo prazo, reversão, reexecução, exclusão segura e checkpoints do workspace.
* **Ferramentas de desenvolvimento integradas** — arquivos, editor, terminal, Git, lista de tarefas, Skills, Agents, MCP, OpenAPI, LSP e automações.

Para executar o cliente a partir do código-fonte, inicie o backend separadamente:

~~~bash
# Terminal 1: backend desktop
soloncode serve 4808

# Terminal 2: cliente desktop
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

O modo de desenvolvimento conecta-se à porta 4808 e não inicia nem detecta automaticamente o processo backend. Consulte o [README do Desktop](soloncode-desktop/README.md) e o [guia de introdução do SolonCode Desk](docs/soloncode-desk-getting-started.md).

## Documentação

Para mais detalhes de configuração, visite nossa [Documentação Oficial](https://solon.noear.org/article/soloncode).

## Contribuir

Se você tem interesse em contribuir com código, leia a [Documentação de Contribuição](https://solon.noear.org/article/623) antes de enviar um PR.

## Desenvolvimento Baseado no SolonCode

Se você usar "soloncode" no nome do seu projeto (por exemplo, "soloncode-dashboard" ou "soloncode-app"), indique no README que o projeto não é desenvolvido oficialmente pela equipe OpenSolon e não possui afiliação.

## Perguntas Frequentes

Qual é a diferença em relação ao Claude Code?

Eles são funcionalmente semelhantes, com diferenças principais:

* Construído com Java, 100% código aberto. Compatível com BiSheng JDK (Huawei) e Harmony PC.
* Totalmente orientado e construído com prompts em chinês
* Independente de provedor. Configure modelos conforme necessário. A iteração de modelos reduzirá lacunas e custos, tornando a configuração flexível importante.
* Suporta simultaneamente a interface de linha de comando (CLI), a interface do navegador (WEB) e a interface IDE de desktop (Desktop).
* Suporta Web, protocolo ACP para comunicação remota.
