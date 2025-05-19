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
import time

# Pydantic models for parsing AI output
class FailedStep(BaseModel):
    num: int    # Step number
    message: str # Failure message


# Model for individual step results
class StepResult(BaseModel):
    num: int        # Step number
    action: str     # Executed command/action
    status: bool    # True for success, False for failure
    duration: float # Time taken in seconds
    feedback: str   # Individual feedback for the step
    fail: Optional[str] # Reason for failure (None if successful)


# Scenario result model with added 'steps' field
class WebTestResult(BaseModel):
    title: str
    status: bool
    duration: float
    feedback: str
    fail: Optional[List[FailedStep]]
    steps: List[StepResult]  # ← List of step-specific results will be here.


# Initialize output parser
output_parser = PydanticOutputParser(pydantic_object=WebTestResult)

# System prompt template
system_prompt = """
You are an AI designed to execute web test scenarios.

- You must perform the actions instructed in each step **sequentially and accurately**.
- If you cannot perform an action as instructed, or if the result is unexpected or abnormal, treat that step as **FAILED**. Clearly explain the reason for failure **in English**.

- When capturing screenshots during the test, you must **only use the `browser_take_screenshot` tool**.
  - Do **not** use the `browser_snapshot` tool.
  - Screenshots should capture **exactly what is visible on the browser screen at that moment**.
  - However, if a step explicitly instructs to take a snapshot, then use the `browser_snapshot` tool.

- When calling `browser_type`, do not include `exact` as a parameter.
- For steps that involve verifying elements, use snapshot information for confirmation.

- **Browser Closing Advisory**:
  - Only use the `browser_close` tool if the scenario **explicitly instructs to close the browser**.
  - Otherwise, **NEVER call `browser_close`**.

- If using a tool in the AI Output Message, you must use **only one tool at a time**.
- After all test steps are completed, provide **overall feedback**.
- If a **failure** occurs during a step in the scenario, the result of that entire scenario must be **FAILED**.
- If a failure occurs, provide detailed feedback in the scenario feedback about which step failed and how.
---

### Output Format (Strictly follow these guidelines):

1. The final response **must be enclosed within a JSON code block**. Output it exactly in the following format:
   - The JSON block must start with **```json** and end with **```**.
   - You may output other text outside the JSON block, but do not modify the JSON internals; maintain the format only.

2. The JSON must include the following items:
#  1) num: Step order (integer)
#  2) action: Executed step instruction (string)
#  3) status: true for success, false for failure (boolean)
#  4) duration: Time taken for the step (in seconds, float)
#  5) feedback: Feedback for each step (string)
#  6) fail: Reason for failure (string), or null if successful

Execute the test scenario diligently and respond in the format below.
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
    raise ValueError("Could not find JSON block.")


def extract_json_from_message(msg: AIMessage) -> str:
    contents = msg.content
    if isinstance(contents, list):
        full = ""
        for part in contents:
            if isinstance(part, dict) and "text" in part:
                full += part["text"]
            elif isinstance(part, str):
                full += part
        return extract_json_block(full)
    elif isinstance(contents, str):
        return extract_json_block(contents)
    raise ValueError("Could not parse AIMessage.content structure.")


# Save JSON result per scenario
def save_result(
        scenario: dict, result: WebTestResult, screenshots: List[str], scenario_dir: str
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
        agent, steps: List[str], screenshot_dir: str
) -> asyncio.Task[Tuple[WebTestResult, List[str]]]:
    return asyncio.create_task(_run_logic(agent, steps, screenshot_dir))


async def _run_logic(
        agent, steps: List[str], screenshot_dir: str
) -> Tuple[WebTestResult, List[str]]:
    response = await agent.ainvoke(
        {
            "messages": [
                {"role": "system", "content": prompt.format()},
                {"role": "user", "content": create_instruction(steps)},
            ]
        },
        config={"recursion_limit": 100},
    )

    saved = []
    count = 1
    for ev in response["messages"]:
        if isinstance(ev, ToolMessage):
            # Artifact-based screenshot
            if getattr(ev, "artifact", None):
                for art in ev.artifact:
                    if getattr(art, "type", "") == "image" and hasattr(art, "data"):
                        fname = f"{count}.png"
                        out = os.path.join(screenshot_dir, fname)
                        with open(out, "wb") as imgf:
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
        agent, scenario: dict, index: int, output_dir: str
) -> asyncio.Task[Tuple[int, WebTestResult, List[str]]]:
    return asyncio.create_task(_run_scenario(agent, scenario, index, output_dir))


async def _run_scenario(
        agent,
        scenario: dict,
        index: int,
        output_dir: str
) -> Tuple[int, WebTestResult, List[str]]:

    # Measure scenario start time
    scenario_start = time.perf_counter()

    scenario_dir = os.path.join(output_dir, f"{index}") # scenario-specific directory
    screenshot_dir = os.path.join(scenario_dir, 'screenshots')
    os.makedirs(screenshot_dir, exist_ok=True)

    # Call AI and save results
    result, screenshots = await _run_logic(agent, scenario.get('steps', []), screenshot_dir)

    # Measure scenario end time and overwrite duration
    scenario_end = time.perf_counter()
    result.duration = scenario_end - scenario_start

    # Use the actual scenario title instead of the one provided by AI
    result.title = scenario.get("title", "")

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

    # Inline CSS to embed directly in the HTML, inspired by Jenkins Design Library
    css = '''
:root {
  --jenkins-font-family: sans-serif;
  --jenkins-bg: #f0f0f0;
  --jenkins-pane-bg: #fff;
  --jenkins-pane-border-color: #ddd;
  --jenkins-table-border-color: #ccc;
  --jenkins-text-color: #333;
  --jenkins-link-color: #007bff;
  --jenkins-button-bg: #007bff;
  --jenkins-button-text-color: #fff;
  --jenkins-success-color: #28a745;
  --jenkins-danger-color: #dc3545;
  --jenkins-warning-color: #ffc107;
  --jenkins-info-color: #17a2b8;
  --jenkins-border-radius: .25rem;
  --jenkins-box-shadow: 0 .125rem .25rem rgba(0,0,0,.075);
}
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}
body {
  font-family: var(--jenkins-font-family);
  background: var(--jenkins-bg);
  color: var(--jenkins-text-color);
  padding: 20px;
  line-height: 1.5;
}
.jenkins-pane { /* Mimicking Jenkins pane */
  background: var(--jenkins-pane-bg);
  border: 1px solid var(--jenkins-pane-border-color);
  border-radius: var(--jenkins-border-radius);
  padding: 20px;
  margin-bottom: 20px;
  box-shadow: var(--jenkins-box-shadow);
}
.header h1 {
  font-size: 1.8rem;
  margin-bottom: 10px;
  color: var(--jenkins-text-color);
}
.header p {
  margin-bottom: 5px;
}
.summary {
  display: flex;
  gap: 20px; /* Increased gap */
  margin-bottom: 30px;
  flex-wrap: wrap; /* Allow wrapping on smaller screens */
}
.summary div {
  background: var(--jenkins-pane-bg);
  padding: 15px; /* Increased padding */
  border-radius: var(--jenkins-border-radius);
  border: 1px solid var(--jenkins-pane-border-color);
  box-shadow: var(--jenkins-box-shadow);
  flex: 1; /* Distribute space */
  min-width: 150px; /* Minimum width for summary items */
}
.summary h2 {
    width: 100%;
    margin-bottom: 10px;
    font-size: 1.5rem;
}
.scenario-card { /* Renamed from .step to avoid conflict with Jenkins step styles */
  background: var(--jenkins-pane-bg);
  border-radius: var(--jenkins-border-radius);
  padding: 20px;
  margin-bottom: 25px;
  border-left: 5px solid var(--jenkins-info-color); /* Default border */
  box-shadow: var(--jenkins-box-shadow);
}
.scenario-card.success {
  border-left-color: var(--jenkins-success-color);
}
.scenario-card.failed {
  border-left-color: var(--jenkins-danger-color);
  background-color: #fff7f7; /* Light red background for failed scenarios */
}
.scenario-card h3 {
    margin-bottom: 10px;
    font-size: 1.25rem;
}
.scenario-card h4 {
    margin-top: 15px;
    margin-bottom: 5px;
    font-size: 1.1rem;
}
.substeps {
  margin-top: 15px;
  padding-left: 15px;
  border-left: 2px solid #eee;
}
.substep-item { /* Renamed from .substep */
  background: #f9f9f9; /* Slightly different background for substeps */
  padding: 10px;
  border-radius: var(--jenkins-border-radius);
  margin-bottom: 10px;
  border: 1px solid #e0e0e0;
}
.substep-item.success {
  border-left: 3px solid var(--jenkins-success-color);
}
.substep-item.failed {
  border-left: 3px solid var(--jenkins-danger-color);
}
.substep-item p {
  margin: 5px 0;
  font-size: 0.9rem;
}
.fail-reason {
    color: var(--jenkins-danger-color);
    font-weight: bold;
}
.screenshots {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 15px;
}
.screenshots img {
  max-width: 200px; /* Limit screenshot preview size */
  height: auto;
  border-radius: var(--jenkins-border-radius);
  border: 1px solid var(--jenkins-pane-border-color);
  box-shadow: var(--jenkins-box-shadow);
  cursor: pointer; /* Indicate they are clickable if you add zoom later */
}
@media (max-width: 768px) {
  .summary {
    flex-direction: column;
  }
  .screenshots img {
    max-width: 100%; /* Full width on smaller screens */
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
    <div class="jenkins-pane header">
        <h1>Test Execution Report</h1>
        <p>Executed At: {test_start.strftime('%Y-%m-%d %H:%M:%S')}</p>
        <p>Duration: {test_duration_ms/1000:.2f}s</p>
    </div>
    
    <div class="jenkins-pane summary">
        <h2>Summary</h2>
        <div>Total Scenarios: {total_steps}</div>
        <div>Passed: {passed_steps}</div>
        <div>Failed: {failed_steps}</div>
    </div>
    
    <div>
        <h2>Detailed Scenarios</h2>
"""

    # Scenario-specific blocks
    for idx, res, screenshots in results:
        status_class = "success" if res.status else "failed"
        html += f"""        <div class="scenario-card {status_class}">
            <h3>Scenario {idx}: {res.title}</h3>
            <p>Status: <strong>{'PASSED' if res.status else 'FAILED'}</strong></p>
            <p>Duration: {res.duration:.2f}s</p>
            <h4>Scenario Feedback:</h4>
            <p>{res.feedback}</p>
"""

        # (Optional) Scenario-level failure reasons
        if res.fail:
            html += "            <div class='fail-reason'><h5>Overall Failure Reasons:</h5><ul>\n"
            for fs in res.fail:
                html += f"                <li>Step {fs.num}: {fs.message}</li>\n"
            html += "            </ul></div>\n"

        # **Add substep output per step**
        html += "            <div class='substeps'>\n"
        html += "                <h4>Detailed Step Results:</h4>\n"
        for step_result_item in res.steps: # Renamed 'step' to 'step_result_item' to avoid conflict
            sub_status_class = "success" if step_result_item.status else "failed"
            html += f"""                <div class="substep-item {sub_status_class}">
                    <p><strong>Step {step_result_item.num}:</strong> {step_result_item.action}</p>
                    <p>Status: {'✅ PASSED' if step_result_item.status else '❌ FAILED'}</p>
                    <p>Feedback: {step_result_item.feedback}</p>
            """
            if step_result_item.fail:
                html += f"                    <p class='fail-reason'>Failure Reason: {step_result_item.fail}</p>\n"
            html += "                </div>\n"
        html += "            </div>\n"

        # Output screenshots (modified)
        for img in screenshots:
            # 'build_id' (renamed from test_id for clarity here) is the build ID string used when calling report
            # 'idx' is the current scenario number (starting from 1)
            html += (
                f'            <img class="screenshot" '
                f'src="screenshot?build={test_id}&scenario={idx}&file={img}" '
                f'alt="Screenshot"/>\n'
            )

        html += "        </div>\n"  # Close scenario block

    html += "</div>\n</body>\n</html>"

    # Save to file
    with open(os.path.join(output_dir, "report.html"), "w", encoding="utf-8") as f:
        f.write(html)


# Main test runner
async def run_test(
        scenarios: List[dict],
        build_num: int,
        base_dir: str,
        provider: str,
        llm_model: str,
        api_key: str,
):
    test_start = datetime.now()
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    test_id = f"{timestamp}_report_{build_num}"
    output_dir = os.path.join(base_dir, test_id)
    os.makedirs(output_dir, exist_ok=True)

    # Select LLM
    if provider == "anthropic":
        model = ChatAnthropic(
            model=llm_model, temperature=0, max_tokens=1000, api_key=api_key
        )
    elif provider == "openai":
        model = ChatOpenAI(
            model=llm_model, temperature=0, max_tokens=1000, api_key=api_key
        )
    else:
        raise ValueError(f"지원되지 않는 provider: {provider}")

    # MCP CLI via stdio
    cur = os.path.dirname(os.path.abspath(__file__))
    mcp_path = os.path.join(cur, "mcp")
    params = StdioServerParameters(command="node", args=["cli.js"], cwd=mcp_path)

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
    print(f"All tests completed: {output_dir}/report.html")


# Entry point
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run Playwright E2E tests using MCP and LLM.")
    parser.add_argument("--build", type=int, required=True, help="Build number for this test run.")
    parser.add_argument(
        "--output_dir", type=str, default="./results", help="Directory to save test results."
    )
    parser.add_argument(
        "--file", type=str, required=True, help="Path to the scenario JSON file."
    )
    args = parser.parse_args()

    load_dotenv()
    prov = os.getenv("LLM_PROVIDER")
    model_key = os.getenv("LLM_MODEL")
    api_k = os.getenv("LLM_API_KEY")

    with open(args.file, "r", encoding="utf-8") as f:
        data = json.load(f)
    scenarios = data.get("scenarios", [])

    asyncio.run(
        run_test(scenarios, args.build, args.output_dir, prov, model_key, api_k)
    )
