import asyncio
from datetime import datetime
import json
import argparse
import os
import base64
from pydantic import BaseModel
from typing import List, Optional, Tuple
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from langchain_mcp_adapters.tools import load_mcp_tools
from langgraph.prebuilt import create_react_agent
from langchain_core.messages import ToolMessage
from langchain_core.messages import AIMessage
from langchain_openai import ChatOpenAI
from langchain_anthropic import ChatAnthropic
from langchain.output_parsers import PydanticOutputParser
from langchain.prompts import PromptTemplate
from dotenv import load_dotenv
import re
import shutil

# Pydantic models for parsing AI output
class FailedStep(BaseModel):
    num: int
    message: str

# 각 스텝 결과용 모델
class StepResult(BaseModel):
    num: int            # 스텝 번호
    action: str         # 수행한 명령문
    status: bool        # 성공/실패 여부
    duration: float     # 소요 시간(초)
    feedback: str       # 개별 피드백
    fail: Optional[str] # 실패 사유(없으면 None)

# 시나리오 결과 모델에 steps 필드 추가
class WebTestResult(BaseModel):
    title: str
    status: bool
    duration: float
    feedback: str
    fail: Optional[List[FailedStep]]
    steps: List[StepResult]   # ← 여기에 스텝별 결과가 리스트로 담깁니다.

# Initialize output parser
output_parser = PydanticOutputParser(pydantic_object=WebTestResult)

# System prompt template
system_prompt = """\
너는 아래 시나리오를 테스트하는 AI야.
각 스텝에서 시키는 대로 행동하고, 실패한 스텝과 이유를 한글로 기록해.
스텝에서 시키는 대로 할 수 없거나, 시킨 대로 한 결과가 이상하면 실패로 처리해.

특히 스크린샷을 캡쳐할 땐 반드시 snapshot을 먼저 만들어야 해.
즉, navigate 이후 바로 take_screenshot을 실행하면 안 되고,
반드시 snapshot → take_screenshot 순서로 실행해야 해.

모든 스텝이 끝난 뒤엔 전체 피드백을 주고, 반드시 아래 형식 그대로 응답해야 해.

최종 JSON은 반드시 아래처럼 **```json 코드블럭 안에만** 포함시켜야 해.
다른 설명은 JSON 블럭 바깥에 써도 되지만, JSON 그 자체는 무조건 ```json 으로 감싸야 해.

# — 여기에 추가 —
# JSON 안에 “steps” 배열을 포함해주세요.
# 각 원소는 다음 필드를 가져야 합니다:
#  1) num: 스텝 순서 (정수)
#  2) action: 수행한 스텝 지시문 (문자열)
#  3) status: 성공이면 true, 실패면 false (불리언)
#  4) duration: 해당 스텝 수행 시간(단위: 초, 실수)
#  5) feedback: 스텝 별 피드백 (문자열)
#  6) fail: 실패 시 사유(문자열), 성공 시 null
#
{format_instructions}
"""

prompt = PromptTemplate(
    template=system_prompt,
    input_variables=[],
    partial_variables={"format_instructions": output_parser.get_format_instructions()},
)

# Utility: Create user instruction from steps
def create_instruction(steps: List[str]) -> str:
    return "\n".join(f"{i+1}. {step}" for i, step in enumerate(steps))

# Utility: Extract JSON block from AIMessage

def extract_json_block(text: str) -> str:
    match = re.search(r"```json\s*(.*?)\s*```", text, re.DOTALL)
    if match:
        return match.group(1)
    raise ValueError("JSON 블럭을 찾을 수 없습니다.")


def extract_json_from_message(msg: AIMessage) -> str:
    contents = msg.content
    if isinstance(contents, list):
        full = ''
        for part in contents:
            if isinstance(part, dict) and 'text' in part:
                full += part['text']
            elif isinstance(part, str):
                full += part
        return extract_json_block(full)
    elif isinstance(contents, str):
        return extract_json_block(contents)
    raise ValueError("AIMessage.content 구조를 파싱할 수 없습니다.")

# Save JSON result per scenario
def save_result(
        scenario: dict,
        result: WebTestResult,
        screenshots: List[str],
        scenario_dir: str
):
    payload = {
        "title": scenario.get("title", ""),
        "status": result.status,
        "duration": result.duration,
        "feedback": result.feedback,
        "fail": [f.model_dump() for f in result.fail] if result.fail else None,
        "screenshots": screenshots,
    }
    with open(os.path.join(scenario_dir, "result.json"), "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

# Core logic: run steps and collect results
def run_logic(
        agent,
        steps: List[str],
        screenshot_dir: str
) -> asyncio.Task[Tuple[WebTestResult, List[str]]]:
    return asyncio.create_task(_run_logic(agent, steps, screenshot_dir))

async def _run_logic(
        agent,
        steps: List[str],
        screenshot_dir: str
) -> Tuple[WebTestResult, List[str]]:
    response = await agent.ainvoke(
        {"messages":[
            {"role":"system","content":prompt.format()},
            {"role":"user","content":create_instruction(steps)}
        ]},
        config={"recursion_limit":100}
    )

    saved = []
    count = 1
    for ev in response["messages"]:
        if isinstance(ev, ToolMessage):
            # Artifact-based screenshot
            if getattr(ev, "artifact", None):
                for art in ev.artifact:
                    if getattr(art, 'type', '') == 'image' and hasattr(art, 'data'):
                        fname = f"{count}.png"
                        out = os.path.join(screenshot_dir, fname)
                        with open(out, 'wb') as imgf:
                            imgf.write(base64.b64decode(art.data))
                        saved.append(fname)
                        count += 1
            # Legacy content-based screenshot
            if isinstance(ev.content, str):
                try:
                    items = json.loads(ev.content)
                    for it in items:
                        if isinstance(it, str) and it.startswith("[screenshot_path] "):
                            src = it.split("[screenshot_path] ", 1)[1].strip()
                            if os.path.isfile(src):
                                fname = f"{count}.png"
                                dst = os.path.join(screenshot_dir, fname)
                                shutil.copy(src, dst)
                                saved.append(fname)
                                count += 1
                except json.JSONDecodeError:
                    pass

    last = response["messages"][-1]
    json_text = extract_json_from_message(last)
    result = output_parser.parse(json_text)
    return result, saved

# Execute a single scenario
def run_scenario(
        agent,
        scenario: dict,
        index: int,
        output_dir: str
) -> asyncio.Task[Tuple[int, WebTestResult, List[str]]]:
    return asyncio.create_task(_run_scenario(agent, scenario, index, output_dir))

async def _run_scenario(
        agent,
        scenario: dict,
        index: int,
        output_dir: str
) -> Tuple[int, WebTestResult, List[str]]:
    scenario_dir = os.path.join(output_dir, f"{index}")
    screenshot_dir = os.path.join(scenario_dir, 'screenshots')
    os.makedirs(screenshot_dir, exist_ok=True)

    result, screenshots = await _run_logic(agent, scenario.get('steps', []), screenshot_dir)
    save_result(scenario, result, screenshots, scenario_dir)
    return index, result, screenshots

# Generate single combined HTML report at root of output_dir
def generate_combined_html_report(
        results: List[Tuple[int, WebTestResult, List[str]]],
        output_dir: str,
        test_start: datetime,
        test_duration_ms: float,
        test_id: str
):
    build_id = os.path.basename(output_dir)
    total_steps = len(results)
    passed_steps = sum(1 for _, r, _ in results if r.status)
    failed_steps = total_steps - passed_steps

    # Inline CSS to embed directly in the HTML
    css = '''
/* Reset */
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}
body {
  font-family: "Segoe UI", Tahoma, sans-serif;
  background: #f9f9f9;
  color: #333;
  padding: 20px;
}

/* 헤더 */
.header {
  background: #4a90e2;
  color: white;
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 20px;
}
.header h1 {
  font-size: 1.8rem;
  margin-bottom: 5px;
}
.header p {
  opacity: 0.9;
}

/* 요약 */
.summary {
  display: flex;
  gap: 15px;
  margin-bottom: 30px;
}
.summary div {
  background: white;
  padding: 10px 15px;
  border-radius: 6px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

/* 시나리오 카드 */
.step {
  background: white;
  border-radius: 8px;
  padding: 15px 20px;
  margin-bottom: 25px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
  transition: transform 0.2s;
}
.step:hover {
  transform: translateY(-3px);
}
.step.success {
  border-left: 6px solid #28a745;
}
.step.failed {
  border-left: 6px solid #dc3545;
  background: #fcebea;
}

/* 서브스텝 */
.substeps {
  margin-top: 15px;
}
.substep {
  background: #f7f9fc;
  padding: 12px;
  border-radius: 6px;
  margin-bottom: 10px;
  border-left: 4px solid #777;
}
.substep.success {
  border-color: #28a745;
}
.substep.failed {
  border-color: #dc3545;
}
.substep p {
  margin: 4px 0;
  font-size: 0.95rem;
}

/* 스크린샷 갤러리 */
.screenshots {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 15px;
}
.screenshots img {
  width: calc(33.333% - 10px);
  border-radius: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

/* 반응형 */
@media (max-width: 600px) {
  .screenshots img {
    width: 100%;
  }
}
'''

    # Build HTML
    html = f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Test Report - {test_start.strftime('%Y-%m-%d %H:%M:%S')}</title>
    <style>
{css}
    </style>
</head>
<body>
    <div class="header">
        <h1>테스트 실행 보고서</h1>
        <p>실행 시간: {test_start.strftime('%Y-%m-%d %H:%M:%S')}</p>
        <p>소요 시간: {int(test_duration_ms)}ms</p>
    </div>
    
    <div class="summary">
        <h2>요약</h2>
        <p>총 시나리오: {total_steps}</p>
        <p>성공: {passed_steps}</p>
        <p>실패: {failed_steps}</p>
    </div>
    
    <div class="steps">
        <h2>상세 시나리오</h2>
"""

    # 시나리오별 블록
    for idx, res, screenshots in results:
        status_str = "success" if res.status else "failed"
        html += f"""        <div class="step {status_str}">
            <h3>시나리오 {idx}: {res.title}</h3>
            <p>상태: {'성공' if res.status else '실패'}</p>
            <p>소요 시간: {res.duration:.2f}s</p>
            <h4>시나리오 피드백</h4>
            <p>{res.feedback}</p>
"""

        # (Optional) 시나리오 레벨 실패 사유
        if res.fail:
            html += "            <div class='fail'><h5>Fail Reasons</h5><ul>\n"
            for fs in res.fail:
                html += f"                <li>Step {fs.num}: {fs.message}</li>\n"
            html += "            </ul></div>\n"

        # **스텝 별 substep 출력 추가**
        html += "            <div class='substeps'>\n"
        html += "                <h4>세부 스텝 결과</h4>\n"
        for step in res.steps:
            sc = "success" if step.status else "failed"
            html += f"""                <div class="substep {sc}">
                    <p><strong>Step {step.num}:</strong> {step.action}</p>
                    <p>상태: {'✅ 성공' if step.status else '❌ 실패'} | 시간: {step.duration:.2f}s</p>
                    <p>피드백: {step.feedback}</p>
            """
            if step.fail:
                html += f"                    <p>실패 사유: {step.fail}</p>\n"
            html += "                </div>\n"
        html += "            </div>\n"

        # 스크린샷 출력 (수정)
        for img in screenshots:
            # build 변수는 report 호출 시 사용된 build ID 문자열
            # idx 는 현재 시나리오 번호 (1부터)
            html += (
                f'            <img class="screenshot" '
                f'src="screenshot?build={test_id}&scenario={idx}&file={img}" '
                f'alt="Screenshot"/>\n'
            )

        html += "        </div>\n"  # 시나리오 블록 닫기

    html += "</div>\n</body>\n</html>"

    # 파일로 저장
    with open(os.path.join(output_dir, "report.html"), "w", encoding="utf-8") as f:
        f.write(html)

# Main test runner
async def run_test(
        scenarios: List[dict],
        build_num: int,
        base_dir: str,
        provider: str,
        llm_model: str,
        api_key: str
):
    test_start = datetime.now()
    timestamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    test_id = f"{timestamp}_report_{build_num}"
    output_dir = os.path.join(base_dir, test_id)
    os.makedirs(output_dir, exist_ok=True)

    # Select LLM
    if provider == 'anthropic':
        model = ChatAnthropic(model=llm_model, temperature=0, max_tokens=1000, api_key=api_key)
    elif provider == 'openai':
        model = ChatOpenAI(model=llm_model, temperature=0, max_tokens=1000, api_key=api_key)
    else:
        raise ValueError(f"지원되지 않는 provider: {provider}")

    # MCP CLI via stdio
    cur = os.path.dirname(os.path.abspath(__file__))
    mcp_path = os.path.join(cur, 'mcp')
    params = StdioServerParameters(command='node', args=['cli.js'], cwd=mcp_path)

    results: List[Tuple[int, WebTestResult, List[str]]] = []
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = await load_mcp_tools(session)
            for idx, scenario in enumerate(scenarios, start=1):
                agent = create_react_agent(model, tools)
                results.append(await _run_scenario(agent, scenario, idx, output_dir))

    # Generate one single HTML report
    test_end = datetime.now()
    duration_ms = (test_end - test_start).total_seconds() * 1000
    generate_combined_html_report(results, output_dir, test_start, duration_ms, test_id)
    print(f"모든 테스트 완료: {output_dir}/report.html")

# Entry point
if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--build', type=int, required=True, help='빌드 번호')
    parser.add_argument('--output_dir', type=str, default='./results', help='결과 저장 디렉토리')
    parser.add_argument('--file', type=str, required=True, help='시나리오 JSON 파일 경로')
    args = parser.parse_args()

    load_dotenv()
    prov = os.getenv('LLM_PROVIDER')
    model_key = os.getenv('LLM_MODEL')
    api_k = os.getenv('LLM_API_KEY')

    with open(args.file, 'r', encoding='utf-8') as f:
        data = json.load(f)
    scenarios = data.get('scenarios', [])

    asyncio.run(run_test(scenarios, args.build, args.output_dir, prov, model_key, api_k))