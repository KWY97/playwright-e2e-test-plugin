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

- **Playwright System Dependencies:** This plugin uses Playwright for browser automation. Playwright requires certain system-level dependencies to function correctly. The `setup.sh` script (executed by the plugin) attempts to install Playwright browsers but skips the installation of system-wide dependencies (`npx playwright install-deps`) as this typically requires `sudo` privileges, which may not be available on Jenkins agents.
  - **Action Required:** You must ensure that all necessary system dependencies for Playwright are pre-installed on your Jenkins agents or within the Docker image used for your agents. Please refer to the official [Playwright documentation on system dependencies](https://playwright.dev/docs/intro#system-requirements) for the specific packages required for your Linux distribution. Failure to pre-install these may result in errors during the `npx playwright install --with-deps chromium` step or when tests are run.

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

### Managing Scenario Files (`JENKINS_HOME/scripts`)

This plugin reads E2E test scenario files (JSON format) from the `JENKINS_HOME/scripts` directory on the Jenkins controller.

- **Responsibility:** Jenkins administrators are responsible for placing and managing scenario files in this directory.
- **Security Note:** Since these files are read from the Jenkins controller's filesystem, ensure that only authorized personnel have write access to the `JENKINS_HOME/scripts` directory to prevent potential security risks. The plugin includes path traversal protection, but proper directory permissions are crucial.
- **File Naming:** When using the `runCoreLogic` step, you provide the `input` parameter, which should be the title of your script (e.g., `my_test_scenario`). The plugin will look for a file named `my_test_scenario.json` in the `JENKINS_HOME/scripts` directory. You can also create and manage these scenario files through the "E2E Test Scripts" link in the Jenkins main sidebar, which provides a UI for editing these JSON files.

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
