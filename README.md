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

### Configuring Jenkins Credentials

To use this plugin securely and effectively, you need to configure two types of credentials in Jenkins:

1.  **Git Repository Credentials**: To allow Jenkins to check out your source code and test scripts from your Git repository.
    -   Go to Jenkins > Manage Jenkins > Credentials > System > Global credentials (unrestricted).
    -   Click "Add Credentials".
    -   **Kind**: Choose "Username with password" (for HTTPS URLs with a username and password/Personal Access Token) or "SSH Username with private key" (for SSH URLs).
    -   Fill in the required details (Username, Password/Token or Private Key).
    -   **ID**: Enter a descriptive ID (e.g., `your-git-credentials-id`). You will use this ID in your Jenkinsfile.
    -   Click "OK" or "Create".

2.  **Environment Variables Credential (`.env` content)**: To securely store the content of your `.env` file (which includes `LLM_PROVIDER`, `LLM_MODEL`, and `LLM_API_KEY`).
    -   Go to Jenkins > Manage Jenkins > Credentials > System > Global credentials (unrestricted).
    -   Click "Add Credentials".
    -   **Kind**: Choose "Secret file".
    -   **File**: Click "Choose File" and upload your `.env` file, or select "Or enter directly" and paste the content of your `.env` file.
    -   **ID**: Enter a descriptive ID (e.g., `your-env-file-credential-id`). You will use this ID in the `playwrightE2ETest` step.
    -   **Description**: (Optional) Add a description.
    -   Click "OK" or "Create".

### How to Use the Plugin

- Pipeline Example

  Ensure your test scenario files (e.g., `my_test_scenario.json`) are committed to your Git repository.

```groovy
pipeline {
  agent any // Specify your agent
  stages {
    stage('Checkout Code') {
      steps {
        // Checkout your source code and test scripts from Git
        // Replace with your actual Git repository URL, branch, and credentials
        git branch: 'main', 
            credentialsId: 'your-git-credentials-id', 
            url: 'https://your-git-repository-url.com/your-project.git'
        echo "Source code and test scripts checked out."
      }
    }
    stage('Run Playwright E2E Test') {
      steps {
        // Execute the Playwright E2E test by specifying the script path in your workspace
        // and the ID of the Jenkins credential storing your .env file content.
        playwrightE2ETest scriptPath: 'path/to/your/scenario.json', // Example: 'tests/e2e/login_test.json'
                         envFileCredentialsId: 'your-env-file-credential-id', // Credential ID for .env content
                         language: 'python' // 'python' or 'typescript' (defaults to 'python')
        echo ">>> Playwright E2E Test was invoked!"
      }
    }
  }
}
```

  **Explanation:**
  - **`stage('Checkout Code')`**: This stage checks out your project's source code, including your Playwright E2E test scenario files, from your Git repository into the Jenkins workspace.
    - `branch`: The Git branch to checkout.
    - `credentialsId`: The ID of the Jenkins credential used to access your Git repository.
    - `url`: The URL of your Git repository.
  - **`stage('Run Playwright E2E Test')`**: This stage executes your E2E test.
    - `scriptPath`: The path to your test scenario file (e.g., `.json` for Python, `.txt` or `.ts` for TypeScript) **relative to the root of your checked-out Git repository (Jenkins workspace)**.
    - `envFileCredentialsId`: The ID of the Jenkins "Secret file" credential that stores the content of your `.env` file (containing `LLM_PROVIDER`, `LLM_MODEL`, `LLM_API_KEY`).
    - `language`: (Optional) The scripting language of your scenario. Can be `python` (default) or `typescript`.

## Issues

When running Jenkins as a Docker Container, it must be run with Root privileges.
(Otherwise, normal execution will not be possible.)

### ⚠️ Platform Limitation

This plugin currently supports **Linux-based environments only**.  
Windows is **not supported** at this time due to underlying system dependencies and command usage.

Please use a Linux-based Jenkins environment (e.g., Debian or Ubuntu) when configuring or executing this plugin.

Report issues and enhancements in the [Jenkins issue tracker](https://github.com/KWY97/playwright-e2e-test-plugin/issues).

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
