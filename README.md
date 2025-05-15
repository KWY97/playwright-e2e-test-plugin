# Playwright E2E Test Plugin

## Introduction

이 플러그인은 Jenkins 환경에서 **Playwright MCP**를 활용하여 **자연어 스크립트 기반반 E2E(End-to-End) 자동화 테스트 파이프라인**을 구축하고 실행할 수 있도록 지원합니다.

최근 LLM(Large Language Model) 및 MCP(Model Context Protocol) 기술의 발전과 함께, 전통적인 자동화 테스트 방식을 넘어서는 새로운 가능성이 열리고 있습니다. 본 플러그인은 Microsoft의 강력한 E2E 테스트 도구인 Playwright와 LLM을 결합하여, 개발자 및 QA 엔지니어가 보다 유연하게 자연어를 통한 자동화 테스트를 관리하고 실행할 수 있도록 돕습니다.

이 플러그인을 사용하면 Jenkins CI/CD 파이프라인 내에서 테스트 스크립트를 쉽게 통합하고 실행할 수 있으며, 관리, 실행 결과 분석 등 자동화 테스트 프로세스의 효율성을 크게 향상시킬 수 있습니다. CI/CD에 자동화된 E2E 테스트를 도입하거나 고도화하려는 분들에게 유용합니다.

## Getting started

### 📦 사전 설치

다음 항목들이 설치되어 있어야 합니다:

- Python 3.12
- Node.js (최신 권장)

### ⚙️ 환경 설정

- 플러그인 환경을 구성하세요

```bash
set -e

echo "🔧 Setting up apt sources..."
echo -e "deb http://deb.debian.org/debian bullseye main\n\
deb http://security.debian.org/ bullseye-security main\n\
deb http://deb.debian.org/debian bullseye-updates main" > /etc/apt/sources.list

echo "Running apt update & upgrade..."
apt update
apt upgrade -y

echo "Installing Python 3, venv, pip..."
apt install python3 python3-venv python3-pip -y

echo "Installing Node.js LTS..."
curl -fsSL https://deb.nodesource.com/setup_lts.x | bash -
apt install -y nodejs
```

### .env 파일 생성

```
LLM_PROVIDER={openai 혹은 anthropic}
LLM_MODEL={사용할 모델}
LLM_API_KEY={API 키}
```

- 지원 모델 목록:
  **Claude:** claude-3-7-sonnet-latest, claude-3-5-sonnet-latest, claude-3-5-haiku-latest
  **GPT:** gpt-4o, gpt-4o-mini

- .env 파일은 credential로 등록해야 합니다.

### Plugin 사용 방법

- Jenkins 파일 작성예시

```bash
pipeline {
  agent any
  stages {
    stage('CoreLogic') {
      steps {
        // script와 함께 credentialsId 를 넘겨줍니다
        runCoreLogic input: 'script 제목', envFileCredentialsId: 'credentialsId'
        echo ">>> CoreLogic was invoked!"
      }
    }
  }
}
```

## Issues

Jenkins를 Docker Container로 실행하는 경우, Root 권한으로 실행해야 합니다.
(그렇지 않으면 정상적인 실행이 되지 않습니다.)

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
