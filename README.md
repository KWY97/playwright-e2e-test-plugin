# Playwright E2E Test Plugin

## Introduction

This plugin supports building and executing an **E2E (End-to-End) automated test pipeline based on natural language scripts** within the Jenkins environment using **Playwright MCP**.

With the recent advancements in LLM (Large Language Model) and MCP (Model Context Protocol) technologies, new possibilities are opening up beyond traditional automated testing methods. By combining Microsoft's powerful Playwright E2E test tool with LLMs, this plugin helps developers and QA engineers manage and execute automated tests more flexibly through natural language.

Using this plugin, you can easily integrate and execute test scripts within your Jenkins CI/CD pipeline, significantly improving the efficiency of the automated testing process, including management and execution results analysis. It is useful for those looking to introduce or enhance automated E2E testing in their CI/CD pipelines.

## Getting started

### 📦 Prerequisites

The following items must be installed:

- Python 3.12
- Node.js (latest recommended)

**Important Note:** Currently, this plugin is only confirmed to work on **Linux-based Jenkins environments**. Windows is not yet supported due to the use of shell-specific commands (e.g., `sed`, `bash`, `source`).

### ⚙️ Environment Setup

- Configure the plugin environment

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

### Create .env File

```
LLM_PROVIDER={openai or anthropic}
LLM_MODEL={model to use}
LLM_API_KEY={API key}
```

- Supported Models:

**Claude:** claude-3-7-sonnet-latest, claude-3-5-sonnet-latest, claude-3-5-haiku-latest
**GPT:** gpt-4o, gpt-4o-mini

- The .env file contents (especially LLM_API_KEY) should be registered as Jenkins credentials for secure storage and use within the plugin configuration.

### How to Use the Plugin

- Pipeline Example

```bash
pipeline {
  agent any
  stages {
    stage('CoreLogic') {
      steps {
        // Pass the script title and credentialsId
        runCoreLogic input: 'script title', envFileCredentialsId: 'credentialsId'
        echo ">>> CoreLogic was invoked!"
      }
    }
  }
}
```

## Issues

When running Jenkins as a Docker Container, it must be run with Root privileges.
(Otherwise, normal execution will not be possible.)

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
