@echo off
chcp 65001 > nul
SETLOCAL

echo 🚀 uv 설치
pip install uv

REM Check if .venv already exists
IF EXIST ".venv\" (
    echo 기존 가상환경이 존재합니다. 생성을 건너뜁니다.
) ELSE (
    echo 가상환경 생성 (Python 3.12)
    uv venv --python 3.12
)

echo ✅ 가상환경 활성화
call .venv\Scripts\activate.bat

echo 📦 패키지 설치
uv sync

echo 🚀 MCP 서버 실행
npx -y @playwright/mcp@latest --port 8005

ENDLOCAL
