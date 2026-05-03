<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a>와 Java로 구축된 오픈소스 코딩 에이전트 (Java8부터 Java26 런타임 환경 지원)</p>
<p>최신 버전: v2026.5.4</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## 설치 및 설정

설치:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

설정 (설치 후 반드시 수정 필요):

* 설치 디렉토리: `~/soloncode/bin/`
* `~/soloncode/config.yml` 설정 파일을 찾아 `models` 설정을 수정합니다 (필수).
* `models` 설정 옵션은 다음을 참조하세요: [모델 설정 및 요청 옵션](https://solon.noear.org/article/1087)

## 실행

콘솔의 임의 디렉토리(작업 공간)에서 `soloncode` 명령을 실행하세요.

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.4
/Users/noear
Tips: (esc) interrupt | /(tab) ls command | @(tab) ls agent

User
> 
```

기능 테스트 (다음 작업을 시도해 보세요, 단순한 것부터 복잡한 것까지):

* `안녕하세요`
* `웹에서 AI MCP 프로토콜을 분석하고 PPT를 생성해 주세요` // 사전에 일부 스킬을 설치하는 것을 권장합니다
* `agent team을 설계해 주세요 (설계안은 demo-dis.md로 저장), solon + java17로 클래식 권한 관리 시스템을 개발해 주세요 (demo-web), 프론트엔드는 vue3를 사용하고 인터페이스는 간결하고 미려하게 만들어 주세요`


## 문서

더 많은 설정 세부 사항은 [공식 문서](https://solon.noear.org/article/soloncode)를 방문하세요.

## 기여

코드 기여에 관심이 있으시다면, PR을 제출하기 전에 [기여 가이드](https://solon.noear.org/article/623)를 읽어주세요.

## SolonCode 기반 개발

프로젝트 이름에 "soloncode"를 사용하는 경우 (예: "soloncode-dashboard" 또는 "soloncode-app"), README에 해당 프로젝트가 OpenSolon 팀에서 공식적으로 개발한 것이 아니며 관련이 없음을 명시해 주세요.

## 자주 묻는 질문: Claude Code 및 OpenCode와의 차이점은 무엇인가요?

기능적으로 유사하지만, 주요 차이점은 다음과 같습니다:

* Java로 구축되었으며 100% 오픈소스입니다.
* 순수 중국어 프롬프트 기반 구동 및 빌드
* 제공자에 구애받지 않습니다. 모델 설정이 필요합니다. 모델의 지속적인 발전으로 격차가 좁아지고 비용이 절감됨에 따라 제공자 독립적인 접근 방식이 중요해지고 있습니다.
* 터미널 명령줄 인터페이스(CLI)에 집중하며, 명령줄을 통해 실행됩니다.
* Web, ACP 프로토콜을 지원하여 원격 통신이 가능합니다.