<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a>와 Java로 구축된 오픈소스 코딩 에이전트 (Java8부터 Java26 런타임 환경 지원)</p>
<p>최신 버전: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="SolonCode Desktop 워크스페이스" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## 설치 및 설정

설치:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

설정 (신규 사용자는 먼저 웹 설정 페이지를 통해 구성하는 것을 권장합니다):

```
soloncode web 0
```

페이지에 진입한 후 "설정 -> 대규모 언어 모델"을 열고 모델을 추가한 후 연결을 테스트하세요.

<img height="260" src="SETTINGS-LLM.png">

## 실행

콘솔의 임의 디렉토리(작업 공간)에서 `soloncode cli`(CLI 대화형) 또는 `soloncode web 0`(Web 대화형) 명령을 실행하세요.

* `soloncode cli`(CLI 대화형)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0`(Web 대화형)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

기능 테스트 (다음 작업을 시도해 보세요, 단순한 것부터 복잡한 것까지):

* `안녕하세요`
* `웹에서 AI MCP 프로토콜을 분석하고 PPT를 생성해 주세요` // 사전에 일부 스킬을 설치하는 것을 권장합니다
* `agent team을 설계해 주세요 (설계안은 demo-dis.md로 저장), solon + java17로 클래식 권한 관리 시스템을 개발해 주세요 (demo-web), 프론트엔드는 vue3를 사용하고 인터페이스는 간결하고 미려하게 만들어 주세요`


## SolonCode Desktop

SolonCode Desktop은 SolonCode의 로컬 IDE 환경입니다. Agent 대화, 프로젝트 파일, Monaco 편집기, 통합 터미널, Git 변경 사항과 작업 실행을 하나의 워크스페이스에 제공합니다. 데스크톱 클라이언트는 Tauri, React, TypeScript로 구축되며 Java CLI 백엔드가 Agent 런타임, 모델 연결 및 도구 실행을 담당합니다.

주요 기능:

* **Agent 실행 모드** — 승인 실행, 자동 편집, 읽기 전용 계획 및 지속적인 Goal 실행.
* **프로젝트 인식 대화** — 이미지와 파일 첨부, 워크스페이스 컨텍스트, 실행 중 작업 추가, 모델·Token·소요 시간 표시.
* **안정적인 세션** — 기록 유지, 장기 메모리, 되돌리기, 다시 실행, 안전한 삭제 및 워크스페이스 체크포인트.
* **통합 개발 도구** — 파일 관리, 코드 편집기, 터미널, Git, 작업 목록, Skills, Agents, MCP, OpenAPI, LSP 및 자동화.

소스에서 실행할 때는 백엔드를 별도로 시작하세요:

~~~bash
# 터미널 1: 데스크톱 백엔드
soloncode serve 4808

# 터미널 2: 데스크톱 클라이언트
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

개발 모드는 4808 포트의 백엔드에 연결하며 백엔드 프로세스를 자동으로 시작하거나 탐지하지 않습니다. 자세한 내용은 [Desktop README](soloncode-desktop/README.md)와 [SolonCode Desk 시작 가이드](docs/soloncode-desk-getting-started.md)를 참조하세요.

## 문서

더 많은 설정 세부 사항은 [공식 문서](https://solon.noear.org/article/soloncode)를 방문하세요.

## 기여

코드 기여에 관심이 있으시다면, PR을 제출하기 전에 [기여 가이드](https://solon.noear.org/article/623)를 읽어주세요.

## SolonCode 기반 개발

프로젝트 이름에 "soloncode"를 사용하는 경우 (예: "soloncode-dashboard" 또는 "soloncode-app"), README에 해당 프로젝트가 OpenSolon 팀에서 공식적으로 개발한 것이 아니며 관련이 없음을 명시해 주세요.

## 자주 묻는 질문: Claude Code와의 차이점은 무엇인가요?

기능적으로 유사하지만, 주요 차이점은 다음과 같습니다:

* Java로 구축되었으며 100% 오픈소스입니다. BiSheng JDK(Huawei) 및 Harmony PC와 호환됩니다.
* 순수 중국어 프롬프트 기반 구동 및 빌드
* 제공자에 구애받지 않습니다. 필요에 따라 모델을 설정할 수 있습니다. 모델의 지속적인 발전으로 격차가 좁아지고 비용이 절감됨에 따라 자유로운 설정이 중요합니다.
* 터미널 명령줄 인터페이스(CLI), 브라우저 인터페이스(WEB), 데스크톱 IDE 인터페이스(Desktop)를 동시에 지원합니다.
* Web, ACP 프로토콜을 지원하여 원격 통신이 가능합니다.
