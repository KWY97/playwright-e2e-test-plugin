#!/bin/bash
echo "Installing uv..."
pip install uv --break-system-packages

echo "Syncing uv dependencies..."
uv sync

# --- Playwright 준비: 캐시 위치 고정 ---
cd mcp
npx playwright install-deps
npm install
# 워크스페이스 안에 브라우저 바이너리 보관
export PLAYWRIGHT_BROWSERS_PATH=./.playwright-browsers

npx playwright install --with-deps chromium

cd ..

echo "Environment setup complete!"
