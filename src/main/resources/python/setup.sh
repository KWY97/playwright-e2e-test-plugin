#!/bin/bash
set -e # Exit immediately if a command exits with a non-zero status.

# Default to python3, but allow overriding via PYTHON_EXECUTABLE
PYTHON_CMD="${PYTHON_EXECUTABLE:-python3}"

echo "Creating Python virtual environment..."
$PYTHON_CMD -m venv .venv

echo "Activating virtual environment..."
# shellcheck source=/dev/null
source .venv/bin/activate 
# The above shellcheck directive is to ignore SC1091, as .venv is created at runtime.

echo "Upgrading pip in virtual environment..."
pip install --upgrade pip

echo "Installing uv in virtual environment..."
pip install uv # --break-system-packages might not be needed if we are in a venv

echo "Syncing uv dependencies using virtual environment's uv..."
./.venv/bin/uv sync

# --- Playwright 준비: 캐시 위치 고정 ---
cd mcp
# npx playwright install-deps # This command may require sudo and attempts to install system-wide dependencies.
# It's recommended to ensure these dependencies are pre-installed on the agent environment or Docker image.
# Refer to Playwright documentation for the list of required dependencies for your OS.
echo "Skipping npx playwright install-deps. Ensure system dependencies are pre-installed on the agent."
npm install
# 워크스페이스 안에 브라우저 바이너리 보관
export PLAYWRIGHT_BROWSERS_PATH=./.playwright-browsers

npx playwright install --with-deps chromium

cd ..

echo "Environment setup complete!"
