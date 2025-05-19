import { TestStep } from "../parser/scenarioParser";
import * as path from "path";
import * as fs from "fs/promises";
import Anthropic from "@anthropic-ai/sdk";
import * as dotenv from "dotenv";
import * as os from "os";
import * as childProcess from "child_process";
import { promisify } from "util";
import { MCPClient } from "../mcp/mcpClient";

const exec = promisify(childProcess.exec);
dotenv.config();

interface StepResult {
  step: TestStep;
  status: "success" | "failed";
  startTime: string;
  endTime: string;
  duration: number;
  screenshot?: string;
  error?: string;
  aiComment?: string;
  pageSnapshot?: string;
  selector?: string;
  elementRef?: string | null;
}

interface TestReport {
  testName: string;
  startTime: string;
  endTime: string;
  duration: number;
  totalSteps: number;
  passedSteps: number;
  failedSteps: number;
  steps: StepResult[];
  finalComment?: string;
  htmlReportURL?: string;
}

function parseCmdArgs() {
  const args = process.argv.slice(2);
  const result: { [key: string]: string | boolean } = {};

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith("--")) {
      const key = arg.substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        result[key] = args[i + 1];
        i++;
      } else {
        result[key] = true;
      }
    }
  }

  return result;
}

async function saveScreenshot(
  screenshotResult: any,
  filePath: string
): Promise<boolean> {
  try {
    // 작업할 데이터가 있는지 확인
    if (!screenshotResult) {
      console.error("스크린샷 데이터가 없습니다");
      return false;
    }

    // 디버그 정보 - 받은 데이터의 구조 로깅
    console.log("스크린샷 결과 타입:", typeof screenshotResult);
    if (typeof screenshotResult === "object") {
      console.log("스크린샷 결과 키:", Object.keys(screenshotResult));
    }

    // 바이너리 데이터 직접 접근 방식
    if (screenshotResult.binary) {
      let imageData = screenshotResult.binary;

      // 문자열인지 확인
      if (typeof imageData === "string") {
        // base64 데이터 URL인지 확인
        const base64Prefix = /^data:image\/[a-zA-Z]+;base64,/;
        if (base64Prefix.test(imageData)) {
          imageData = imageData.replace(base64Prefix, "");
        }

        // 버퍼를 파일로 쓰기
        await fs.writeFile(filePath, Buffer.from(imageData, "base64"));
        console.log(`바이너리 데이터를 사용하여 스크린샷 저장됨: ${filePath}`);
        return true;
      }
    }

    // content 배열 접근 방식
    if (screenshotResult.content && Array.isArray(screenshotResult.content)) {
      for (const item of screenshotResult.content) {
        if (item.type === "image" && item.data) {
          let data = item.data;

          // 문자열인 경우만 처리
          if (typeof data === "string") {
            // base64 접두사가 있으면 제거
            const base64Prefix = /^data:image\/[a-zA-Z]+;base64,/;
            if (base64Prefix.test(data)) {
              data = data.replace(base64Prefix, "");
            }

            await fs.writeFile(filePath, Buffer.from(data, "base64"));
            console.log(`content 배열에서 스크린샷 저장됨: ${filePath}`);
            return true;
          }
        }
      }
    }

    // 원시 버퍼 데이터 시도
    if (Buffer.isBuffer(screenshotResult)) {
      await fs.writeFile(filePath, screenshotResult);
      console.log(`원시 버퍼로 스크린샷 저장됨: ${filePath}`);
      return true;
    }

    // 마지막 수단: 결과에서 base64 인코딩된 문자열 찾기
    const resultString = JSON.stringify(screenshotResult);
    const base64Pattern = /"data":"([A-Za-z0-9+/=]+)"/;
    const base64Match = resultString.match(base64Pattern);

    if (base64Match && base64Match[1]) {
      await fs.writeFile(filePath, Buffer.from(base64Match[1], "base64"));
      console.log(`추출된 base64 데이터로 스크린샷 저장됨: ${filePath}`);
      return true;
    }

    // 아무것도 작동하지 않으면 디버그 정보 저장
    console.error("유효한 스크린샷 데이터를 추출하지 못했습니다");
    const debugPath = `${filePath}.debug.json`;
    await fs.writeFile(debugPath, JSON.stringify(screenshotResult, null, 2));
    console.log(`디버그 정보가 저장됨: ${debugPath}`);

    return false;
  } catch (error) {
    console.error("스크린샷 저장 오류:", error);
    return false;
  }
}

export class AdaptivePlaywrightExecutor {
  private mcpClient: MCPClient;
  private outputDir: string;
  private testRunDir: string;
  private screenshotsDir: string;
  private testReport: TestReport;
  private anthropic: Anthropic;
  private browserContextId: string | null = null;
  private pageId: string | null = null;
  private buildNumber: string;

  constructor() {
    const jenkinsHome = process.env.JENKINS_HOME || process.cwd();
    this.outputDir = path.join(jenkinsHome, "results");

    const now = new Date();
    const timestamp = now
      .toLocaleString("sv-SE") // ISO 형식 비슷한 로컬 시간 (예: 2025-05-16 16:22:11)
      .replace(/[: ]/g, "-") // 파일 시스템에서 안전하게 사용
      .replace(",", "");
    const cmdArgs = parseCmdArgs();
    this.buildNumber = cmdArgs.build ? `${cmdArgs.build}` : "";
    this.testRunDir = path.join(
      this.outputDir,
      `test-run-${timestamp}_${this.buildNumber}`
    );
    this.screenshotsDir = path.join(this.testRunDir, "1", "screenshots");
    this.mcpClient = new MCPClient();
    this.anthropic = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });
    this.testReport = {
      testName: "Natural Language Test",
      startTime: "",
      endTime: "",
      duration: 0,
      totalSteps: 0,
      passedSteps: 0,
      failedSteps: 0,
      steps: [],
    };
  }

  async initialize() {
    // 디렉토리 구조 생성
    await fs.mkdir(this.testRunDir, { recursive: true });
    await fs.mkdir(this.screenshotsDir, { recursive: true });

    console.log(`테스트 실행 디렉토리: ${this.testRunDir}`);
    console.log(`스크린샷 디렉토리: ${this.screenshotsDir}`);

    try {
      // MCP 클라이언트 연결
      await this.mcpClient.connect();

      // 연결 후 잠시 대기 (안정화)
      await new Promise((resolve) => setTimeout(resolve, 500));

      // 브라우저 시작
      console.log("브라우저 시작 중...");
      const launchResult = await this.mcpClient.executeAction("browserLaunch", {
        headless: false,
        // slowMo: 100, // 작업 사이 지연 시간 증가
        args: [
          "--window-size=1920,1080",
          "--disable-features=site-per-process",
          "--no-sandbox",
          "--disable-web-security",
          "--lang=ko", // 언어 설정 추가
          "--font-render-hinting=medium", // 폰트 렌더링 힌팅 설정
          "--enable-font-antialiasing", // 폰트 안티앨리어싱 활성화
        ],
        // 자동으로 대화상자 허용 (가능한 경우)
        acceptDownloads: true,
        hasTouch: false,
        ignoreHTTPSErrors: true,
        bypassCSP: true,
      });

      console.log("브라우저 시작 결과:", launchResult);

      // 브라우저가 완전히 초기화될 때까지 대기
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // 브라우저 컨텍스트 생성
      console.log("브라우저 컨텍스트 생성 중...");
      const contextResult = await this.mcpClient.executeAction(
        "browserNewContext",
        {
          incognito: true, // 시크릿 모드 활성화
        }
      );
      this.browserContextId = contextResult.contextId;
      console.log("브라우저 컨텍스트 생성 결과:", contextResult);

      // 컨텍스트 생성 후 대기
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // 페이지 생성
      console.log("페이지 생성 중...");
      const pageResult = await this.mcpClient.executeAction("contextNewPage", {
        context: this.browserContextId,
      });
      this.pageId = pageResult.pageId;
      console.log("페이지 생성 결과:", pageResult);

      // 페이지 초기화 대기
      await new Promise((resolve) => setTimeout(resolve, 1000));

      console.log("브라우저와 페이지 초기화 완료");
    } catch (error) {
      console.error("브라우저 초기화 실패:", error);
      throw error;
    }
  }

  async executeSteps(steps: TestStep[]) {
    console.log("테스트 실행을 시작합니다...");
    console.log(`결과는 다음 위치에 저장됩니다: ${this.testRunDir}`);

    this.testReport.startTime = new Date().toISOString();
    this.testReport.totalSteps = steps.length;

    for (let i = 0; i < steps.length; i++) {
      const step = steps[i];
      console.log(`\n단계 ${i + 1} 실행: ${step.description}`);

      const stepResult: StepResult = {
        step: step,
        status: "success",
        startTime: new Date().toISOString(),
        endTime: "",
        duration: 0,
      };

      try {
        // 현재 페이지 스냅샷 캡처 (AI 분석용)
        console.log(`📸 단계 실행 전 페이지 스냅샷 캡처 중...`);
        const pageSnapshot = await this.getPageSnapshot();
        stepResult.pageSnapshot = pageSnapshot;

        // 스냅샷 기반 요소 정보 분석을 가장 먼저 실행
        // 이 분석 결과는 'click', 'fill' 등의 상호작용 액션에서 사용됨
        let elementInfo = null;
        if (["click", "fill"].includes(step.action)) {
          console.log(`🔍 AI 요소 분석 사전 실행 중...`);
          elementInfo = await this.getElementInfoFromAI(step, pageSnapshot);

          if (elementInfo) {
            console.log(
              `✅ AI 요소 분석 완료: selector=${elementInfo.selector}, ref=${elementInfo.ref}`
            );
            stepResult.selector = elementInfo.selector;
            stepResult.elementRef = elementInfo.ref;
          } else {
            console.warn(`⚠️ AI 요소 분석 결과 없음`);
          }
        }

        // 단계별 처리
        switch (step.action) {
          case "navigate":
            await this.handleNavigate(step);
            break;
          case "click":
            await this.handleClick(step, stepResult, elementInfo);
            break;
          case "fill":
            await this.handleFill(step, stepResult, elementInfo);
            break;
          case "press":
            await this.handlePress(step);
            break;
          case "wait":
            await this.handleWait(step);
            break;
          case "screenshot":
            await this.handleScreenshot(step);
            break;
          default:
            console.warn(`알 수 없는 액션: ${step.action}`);
        }

        stepResult.status = "success";
        this.testReport.passedSteps++;
        console.log(`단계 성공: ${step.description}`);

        // 각 단계 후 스크린샷 캡처 (screenshot 액션이 아닌 경우)
        if (step.action !== "screenshot") {
          const autoScreenshotPath = path.join(
            this.screenshotsDir,
            `auto-step-${i + 1}-${new Date()
              .toISOString()
              .replace(/[:.]/g, "-")}.png`
          );

          const autoScreenshotResult = await this.mcpClient.executeAction(
            "pageScreenshot",
            {
              page: this.pageId,
              fullPage: true,
            }
          );

          const saved = await saveScreenshot(
            autoScreenshotResult,
            autoScreenshotPath
          );
          if (saved) {
            stepResult.screenshot = autoScreenshotPath;
          }
        }

        // AI에게 결과 분석 요청
        stepResult.aiComment = await this.getAIComment(step, stepResult);
      } catch (error) {
        console.error(`단계 "${step.description}" 실행 오류:`, error);
        stepResult.status = "failed";
        stepResult.error =
          error instanceof Error ? error.message : String(error);
        this.testReport.failedSteps++;

        if (this.isDialogError(error)) {
          try {
            console.log("🔔 대화 상자 관련 오류 감지, 대화 상자 처리 시도...");
            await this.mcpClient.handleDialog(true); // 대화 상자 수락
            console.log("✅ 대화 상자 처리 후 계속 진행");
          } catch (dialogError) {
            console.error("❌ 대화 상자 처리 실패:", dialogError);
          }
        }

        // 에러 발생 시 스크린샷
        const errorScreenshotPath = path.join(
          this.screenshotsDir,
          `error-step-${i + 1}-${new Date()
            .toISOString()
            .replace(/[:.]/g, "-")}.png`
        );

        try {
          const errorScreenshotResult = await this.mcpClient.executeAction(
            "pageScreenshot",
            {
              page: this.pageId,
              fullPage: true,
            }
          );

          const saved = await saveScreenshot(
            errorScreenshotResult,
            errorScreenshotPath
          );
          if (saved) {
            stepResult.screenshot = errorScreenshotPath;
          }
        } catch (screenshotError) {
          console.error("에러 발생 후 스크린샷 촬영 실패:", screenshotError);
        }

        stepResult.aiComment = await this.getAIComment(step, stepResult);
      }

      stepResult.endTime = new Date().toISOString();
      stepResult.duration =
        new Date(stepResult.endTime).getTime() -
        new Date(stepResult.startTime).getTime();
      this.testReport.steps.push(stepResult);

      // 각 단계 사이에 대기
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }

    this.testReport.endTime = new Date().toISOString();
    this.testReport.duration =
      new Date(this.testReport.endTime).getTime() -
      new Date(this.testReport.startTime).getTime();

    // 최종 테스트 결과에 대한 AI 코멘트
    console.log("\n테스트 실행 완료, AI 분석 결과 생성 중...");
    this.testReport.finalComment = await this.getFinalTestComment();

    // HTML 리포트 생성
    console.log("HTML 보고서 생성 중...");
    const htmlReportPath = await this.generatePlaywrightReport();
    this.testReport.htmlReportURL = htmlReportPath;

    // 테스트 리포트 저장
    // const reportPath = path.join(this.testRunDir, `test-report.json`);
    // await fs.writeFile(reportPath, JSON.stringify(this.testReport, null, 2));

    // console.log(`\n테스트 보고서 저장 완료: ${reportPath}`);

    // 콘솔에 요약 출력
    console.log("\n=== 테스트 요약 ===");
    console.log(`총 단계: ${this.testReport.totalSteps}`);
    console.log(`성공: ${this.testReport.passedSteps}`);
    console.log(`실패: ${this.testReport.failedSteps}`);
    console.log(`소요 시간: ${this.testReport.duration}ms`);
    console.log(`HTML 보고서: ${this.testReport.htmlReportURL}`);
    console.log("\nAI 최종 분석:");
    console.log(this.testReport.finalComment);
  }

  // adaptivePlaywrightExecutor.ts에 추가
  private isDialogError(error: any): boolean {
    if (typeof error === "object" && error !== null) {
      const errorStr = JSON.stringify(error).toLowerCase();
      return (
        errorStr.includes("modal state") &&
        errorStr.includes("dialog") &&
        (errorStr.includes("alert") ||
          errorStr.includes("confirm") ||
          errorStr.includes("prompt"))
      );
    }
    return false;
  }

  async cleanup() {
    try {
      if (this.pageId && this.browserContextId) {
        // 페이지 닫기
        await this.mcpClient.executeAction("pageClose", {
          page: this.pageId,
        });

        // 브라우저 컨텍스트 닫기
        await this.mcpClient.executeAction("contextClose", {
          context: this.browserContextId,
        });
      }

      // MCP 클라이언트 연결 해제
      await this.mcpClient.disconnect();

      console.log("브라우저 및 MCP 클라이언트 정리 완료");
    } catch (error) {
      console.error("정리 과정에서 오류 발생:", error);
    }
  }

  // 실제 액션 구현

  private async handleNavigate(step: TestStep): Promise<void> {
    const url = [step.value, step.target].find(
      (v) => typeof v === "string" && v.startsWith("http")
    );

    if (!url) {
      throw new Error("유효한 URL이 지정되지 않았습니다.");
    }

    console.log(`${url}로 이동 중...`);
    await this.mcpClient.executeAction("pageGoto", {
      page: this.pageId,
      url: url,
    });

    // await this.mcpClient.executeAction('pageWaitForLoadState', {
    //   time: 0.5,
    // });

    // 페이지 로딩 대기
    // await new Promise((resolve) => setTimeout(resolve, 100));
    console.log(`페이지 이동 완료: ${url}`);
  }

  private async handleClick(
    step: TestStep,
    stepResult: StepResult,
    preAnalyzedElementInfo: {
      selector?: string;
      ref?: string | null;
    } | null = null
  ): Promise<void> {
    console.log(`🖱️ 클릭 시작: ${step.description}`);

    await new Promise((resolve) => setTimeout(resolve, 1000));

    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        console.log(`🔄 클릭 시도 #${attempt + 1}...`);
        let selector = step.target || "";
        let ref = null;

        // 이미 AI 분석 결과가 있는지 확인
        if (
          preAnalyzedElementInfo &&
          (preAnalyzedElementInfo.selector || preAnalyzedElementInfo.ref)
        ) {
          console.log(`🧠 사전 분석된 요소 정보 사용`);
          selector = preAnalyzedElementInfo.selector || selector;
          ref = preAnalyzedElementInfo.ref;
        } else if (selector) {
          console.log(`직접 선택자 사용: ${selector}`);
        } else {
          console.warn(
            "⚠️ 선택자 및 사전 분석된 요소 정보가 없음. 재분석 필요"
          );

          // 사전 분석된 정보가 없는 경우, 현재 스냅샷으로 다시 분석
          const snapshot = await this.getPageSnapshot();
          const elementInfo = await this.getElementInfoFromAI(step, snapshot);

          if (elementInfo?.selector) {
            selector = elementInfo.selector;
            ref = elementInfo.ref;
          }
        }

        // 최종 선택자와 ref 정보 기록
        console.log(
          `🧩 최종 사용 선택자/ref: selector=${selector}, ref=${ref}`
        );
        stepResult.selector = selector;
        stepResult.elementRef = ref;

        if (ref) {
          await this.mcpClient.executeAction("pageClick", {
            ref,
            element: step.description || "클릭 대상",
          });
        } else if (selector) {
          await this.mcpClient.executeAction("pageClick", {
            ref: null,
            element: selector,
          });
        }

        try {
          // 더 긴 지연 시간을 주어 대화상자가 나타날 시간을 확보
          console.log("⏱️ 대화상자 확인을 위해 1초 대기...");
          await new Promise((resolve) => setTimeout(resolve, 1000));

          // 대화상자가 있는지 여러 번 확인 (최대 3회)
          for (let i = 0; i < 3; i++) {
            const dialogFound = await this.checkAndHandleDialog();
            if (dialogFound) {
              console.log(`✅ 대화상자 처리 완료 (시도 #${i + 1})`);
              break;
            }

            if (i < 2) {
              // 잠시 기다렸다가 다시 확인
              await new Promise((resolve) => setTimeout(resolve, 500));
            }
          }
        } catch (dialogError) {
          console.warn(`⚠️ 대화상자 확인/처리 실패: ${dialogError}`);
        }
        // await this.mcpClient.executeAction('pageWaitForLoadState', {
        //   page: this.pageId,
        //   state: 'networkidle',
        // });

        return;
      } catch (error) {
        console.error(`❌ 클릭 시도 #${attempt + 1} 실패:`, error);

        // 오류 응답에서 대화 상자 관련 내용 확인
        if (this.isDialogError(error)) {
          // isClickWithDialogError 대신 isDialogError 사용
          try {
            console.log("🔔 클릭 중 대화 상자 감지됨, 처리 중...");

            // MCPClient의 browser_handle_dialog 도구 직접 호출
            await this.mcpClient.executeAction("browser_handle_dialog", {
              accept: true,
              // promptText 생략 (alert에는 필요 없음)
            });

            console.log("✅ 대화 상자 처리 완료, 클릭 성공으로 간주");
            return; // 성공으로 처리
          } catch (dialogError) {
            console.error("❌ 대화 상자 처리 실패:", dialogError);
          }
        }

        if (attempt === 2) throw error;
        await new Promise((resolve) => setTimeout(resolve, 3000));
      }
    }

    throw new Error(`${step.description} 클릭 실패: 최대 재시도 횟수 초과`);
  }

  // 클릭 오류가 dialog와 관련된 것인지 확인
  private isClickWithDialogError(error: any): boolean {
    if (typeof error === "object" && error !== null) {
      const errorStr = JSON.stringify(error).toLowerCase();
      return (
        (errorStr.includes("modal state") || errorStr.includes("dialog")) &&
        errorStr.includes("handle")
      );
    }
    return false;
  }

  private async checkAndHandleDialog(): Promise<boolean> {
    try {
      // 먼저 browser_handle_dialog 도구가 사용 가능한지 확인
      try {
        // 대화상자가 있는지 확인하는 더 직접적인 방법
        const result = await this.mcpClient.executeAction(
          "browser_snapshot",
          {}
        );

        // 스냅샷 내용을 문자열로 변환하고 alert 또는 dialog 키워드 검색
        const resultText = JSON.stringify(result);

        if (
          resultText.includes("alert dialog") ||
          resultText.includes("modal state") ||
          resultText.includes("회원가입이 성공적으로 완료")
        ) {
          console.log("🔍 대화 상자 감지됨, 처리 중...");

          // 대화상자 처리
          await this.mcpClient.executeAction("browser_handle_dialog", {
            accept: true,
          });

          console.log("✅ 대화 상자 처리 완료");
          return true;
        }
      } catch (error) {
        // 오류 발생 시 오류 메시지에서 대화상자 관련 텍스트 확인
        const errorStr = JSON.stringify(error);

        if (
          errorStr.includes("modal state") ||
          errorStr.includes("dialog") ||
          errorStr.includes("alert")
        ) {
          console.log("🔍 오류에서 대화 상자 감지됨, 처리 중...");

          // 대화상자 처리
          await this.mcpClient.executeAction("browser_handle_dialog", {
            accept: true,
          });

          console.log("✅ 대화 상자 처리 완료");
          return true;
        }
      }

      return false;
    } catch (error) {
      console.error("❌ 대화 상자 확인 및 처리 실패:", error);
      return false;
    }
  }

  async executeActionWithDialogCheck(action: string, args: any): Promise<any> {
    try {
      const result = await this.mcpClient.executeAction(action, args);
      return result;
    } catch (error) {
      // 오류 메시지에서 대화상자 관련 텍스트 확인
      const errorStr = JSON.stringify(error);
      if (this.isDialogError(errorStr)) {
        console.log("대화 상자 감지됨, 처리 시도 중...");
        await this.mcpClient.handleDialog(true);
        console.log("대화 상자 처리 후 작업 재시도...");

        // 대화 상자 처리 후 원래 액션 다시 시도 (선택적)
        return await this.mcpClient.executeAction(action, args);
      }
      throw error; // 대화 상자 관련 오류가 아니면 오류 다시 발생
    }
  }

  // dialog 확인 전용 함수 (더 가벼운 버전)
  private async checkForDialog(): Promise<boolean> {
    try {
      // MCP 프로토콜이 dialog 상태를 확인하는 메서드를 가지고 있다고 가정
      const modalStateResult = await this.mcpClient.executeAction(
        "browser_get_modal_state",
        {
          page: this.pageId,
        }
      );

      if (
        modalStateResult &&
        modalStateResult.content &&
        modalStateResult.content[0] &&
        modalStateResult.content[0].text &&
        modalStateResult.content[0].text.includes("dialog")
      ) {
        // dialog가 있으면 처리
        console.log(`🔍 대화 상자 감지됨: ${modalStateResult.content[0].text}`);
        await this.mcpClient.handleDialog(true);
        return true;
      }
      return false;
    } catch (error) {
      // 오류 메시지에서 dialog 정보 확인
      const errorStr = JSON.stringify(error);
      if (errorStr.includes("modal state") && errorStr.includes("dialog")) {
        await this.mcpClient.handleDialog(true);
        return true;
      }
      return false;
    }
  }

  private async handlePress(step: TestStep): Promise<void> {
    console.log(`⌨️ 키 입력 시작: ${step.description}`);

    const key = step.value || "Enter";

    try {
      // 활성 요소에 키 입력
      await this.mcpClient.executeAction("pagePress", {
        page: this.pageId,
        key: key,
      });

      console.log(`✅ 키 입력 완료: ${key}`);

      // 페이지 로딩 대기
      // await this.mcpClient.executeAction('pageWaitForLoadState', {
      //   page: this.pageId,
      //   state: 'networkidle',
      //   timeout: 2000,
      // });
    } catch (error) {
      console.error(`키 입력 실패: ${error}`);
      throw error;
    }
  }

  private async handleWait(step: TestStep): Promise<void> {
    console.log(`⏱️ 대기 시작: ${step.description}`);

    const timeout = step.value ? parseInt(step.value) : 5000;

    try {
      if (step.target) {
        // 특정 요소 대기
        console.log(`요소 대기: ${step.target}`);

        // 선택자가 존재하는지 확인할 때까지 대기
        await this.mcpClient.executeAction("pageEvaluate", {
          page: this.pageId,
          expression: `(selector, timeout) => {
            return new Promise((resolve, reject) => {
              const startTime = Date.now();
              
              const checkElement = () => {
                const element = document.querySelector(selector);
                if (element) {
                  // 요소 강조 표시 (디버깅용)
                  const originalStyle = element.style.cssText;
                  element.style.border = '3px solid green';
                  setTimeout(() => { element.style.cssText = originalStyle; }, 1000);
                  
                  resolve(true);
                  return;
                }
                
                // 시간 초과 확인
                if (Date.now() - startTime > timeout) {
                  reject(new Error('요소 대기 시간 초과'));
                  return;
                }
                
                // 다시 확인
                setTimeout(checkElement, 100);
              };
              
              checkElement();
            });
          }`,
          arg: [step.target, timeout],
        });

        console.log(`✅ 요소 발견: ${step.target}`);
      } else {
        // 지정된 시간 동안 대기
        console.log(`${timeout}ms 동안 대기...`);
        await new Promise((resolve) => setTimeout(resolve, timeout));
      }

      console.log(`✅ 대기 완료`);
    } catch (error) {
      console.error(`대기 실패: ${error}`);
      throw error;
    }
  }

  private async handleScreenshot(step: TestStep): Promise<void> {
    console.log(`📸 스크린샷 캡처 시작: ${step.description}`);

    try {
      const screenshotPath = path.join(
        this.screenshotsDir,
        `manual-${new Date().toISOString().replace(/[:.]/g, "-")}.png`
      );

      const screenshotResult = await this.mcpClient.executeAction(
        "pageScreenshot",
        {
          page: this.pageId,
          fullPage: true,
        }
      );

      const saved = await saveScreenshot(screenshotResult, screenshotPath);
      if (saved) {
        console.log(`✅ 스크린샷 저장 완료: ${screenshotPath}`);
      } else {
        throw new Error("스크린샷 저장 실패");
      }
    } catch (error) {
      console.error(`스크린샷 캡처 실패: ${error}`);
      throw error;
    }
  }

  private async handleFill(
    step: TestStep,
    stepResult: StepResult,
    preAnalyzedElementInfo: {
      selector?: string;
      ref?: string | null;
    } | null = null
  ): Promise<void> {
    console.log(`⌨️ 입력 시작: ${step.description}`);

    if (!step.value) throw new Error("입력할 값이 지정되지 않았습니다.");

    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        console.log(`🔄 입력 시도 #${attempt + 1}...`);

        let selector = step.target || "";
        let ref = null;

        // 이미 AI 분석 결과가 있는지 확인
        if (
          preAnalyzedElementInfo &&
          (preAnalyzedElementInfo.selector || preAnalyzedElementInfo.ref)
        ) {
          console.log(`🧠 사전 분석된 요소 정보 사용`);
          selector = preAnalyzedElementInfo.selector || selector;
          ref = preAnalyzedElementInfo.ref;
        } else if (selector) {
          console.log(`직접 선택자 사용: ${selector}`);
        } else {
          console.warn(
            "⚠️ 선택자 및 사전 분석된 요소 정보가 없음. 재분석 필요"
          );

          // 사전 분석된 정보가 없는 경우, 현재 스냅샷으로 다시 분석
          const snapshot = await this.getPageSnapshot();
          const elementInfo = await this.getElementInfoFromAI(step, snapshot);

          if (elementInfo?.selector) {
            selector = elementInfo.selector;
            ref = elementInfo.ref;
          }
        }

        // 최종 선택자와 ref 정보 기록
        console.log(
          `🧩 최종 사용 선택자/ref: selector=${selector}, ref=${ref}`
        );
        stepResult.selector = selector;
        stepResult.elementRef = ref;

        if (ref) {
          try {
            await this.mcpClient.executeAction("pageFill", {
              ref: ref,
              element: step.description || "입력 필드",
              text: step.value,
              // submit: false,
              // slowly: false
            });
            console.log(`✅ ref를 사용한 입력 완료: ${ref}`);
            return;
          } catch (error) {
            console.warn(`⚠️ ref 입력 실패: ${error}`);
          }
        }

        if (selector) {
          try {
            const exists = await this.mcpClient.executeAction("pageEvaluate", {
              page: this.pageId,
              expression: `() => document.querySelector('${selector}') !== null`,
            });

            if (exists.result) {
              await this.mcpClient.executeAction("pageEvaluate", {
                page: this.pageId,
                expression: `(value) => {
                const el = document.querySelector('${selector}');
                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                  el.value = '';
                  el.value = value;
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                  return true;
                }
                return false;
              }`,
                arg: step.value,
              });
              console.log(`✅ 선택자 입력 성공: ${selector}`);
              return;
            }
          } catch (error) {
            console.warn(`⚠️ 선택자 입력 실패: ${error}`);
          }
        }

        if (attempt === 2) {
          console.log("🔍 마지막 수단: 포커스된 요소에 직접 입력");

          await this.mcpClient.executeAction("pageEvaluate", {
            page: this.pageId,
            expression: `(value) => {
            const el = document.activeElement;
            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
              el.value = value;
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              return true;
            }
            return false;
          }`,
            arg: step.value,
          });
          console.log(`✅ 포커스된 요소 입력 완료`);
          return;
        }

        await new Promise((r) => setTimeout(r, 500));
      } catch (error) {
        console.error(`❌ 입력 시도 #${attempt + 1} 실패:`, error);
        if (attempt === 2) throw error;
        await new Promise((r) => setTimeout(r, 500));
      }
    }

    throw new Error(`${step.description} 입력 실패: 최대 재시도 횟수 초과`);
  }

  private async getPageSnapshot(): Promise<string> {
    try {
      console.log("📸 스냅샷 캡처 시작...");

      // 페이지가 안정화될 시간 제공
      await new Promise((resolve) => setTimeout(resolve, 500));

      // 단일 browser_snapshot 호출로 페이지 정보 획득
      const snapshotResult = await this.mcpClient.executeAction(
        "browser_snapshot",
        {
          page: this.pageId,
        }
      );

      console.log(`📄 스냅샷 획득 완료`);

      // 스냅샷 결과에서 필요한 정보 추출
      let url = "unknown";
      let title = "unknown";
      let elements: any[] = [];
      let rawSnapshotText = "스냅샷 텍스트 없음";

      // 스냅샷 결과에서 정보 파싱
      if (snapshotResult && snapshotResult.content) {
        // 텍스트 콘텐츠 추출
        const textContent = snapshotResult.content
          .filter((item: any) => item.type === "text")
          .map((item: any) => item.text)
          .join("\n");

        // 원본 스냅샷 텍스트 저장
        rawSnapshotText = textContent;

        // URL 추출
        const urlMatch = textContent.match(/- Page URL: (.+)/);
        if (urlMatch) {
          url = urlMatch[1].trim();
        }

        // 제목 추출
        const titleMatch = textContent.match(/- Page Title: (.+)/);
        if (titleMatch) {
          title = titleMatch[1].trim();
        }

        // 요소 정보 추출 - ref 태그가 있는 요소들 파싱
        const elementMatches = [
          ...textContent.matchAll(/- ([^\n]+) \[ref=([^\]]+)\]/g),
        ];
        elements = elementMatches.map((match, index) => {
          const fullText = match[1].trim();
          const ref = match[2].trim();

          // 요소 유형 및 속성 파싱
          let tagName = "unknown";
          let id = "";
          let className = "";
          let placeholder = "";
          let value = "";
          let text = fullText;

          // 태그 유형 추출 시도
          const tagMatch = fullText.match(/<([a-z0-9]+)/i);
          if (tagMatch) {
            tagName = tagMatch[1].toLowerCase();
          }

          // ID 추출 시도
          const idMatch = fullText.match(/id="([^"]+)"/);
          if (idMatch) {
            id = idMatch[1];
          }

          // 클래스 추출 시도
          const classMatch = fullText.match(/class="([^"]+)"/);
          if (classMatch) {
            className = classMatch[1];
          }

          // 입력 필드 속성 추출 (placeholder, type 등)
          if (tagName === "input" || tagName === "textarea") {
            const placeholderMatch = fullText.match(/placeholder="([^"]+)"/);
            if (placeholderMatch) {
              placeholder = placeholderMatch[1];
            }

            const valueMatch = fullText.match(/value="([^"]+)"/);
            if (valueMatch) {
              value = valueMatch[1];
            }
          }

          // 요소 가시성 - 스냅샷에 표시되는 요소는 기본적으로 가시적이라고 가정
          const visible = true;

          return {
            index,
            tagName,
            id,
            className,
            placeholder,
            value,
            text,
            ref,
            visible,
          };
        });
      }

      // 요소가 부족하게 추출된 경우 경고
      if (elements.length === 0) {
        console.warn(
          "⚠️ 스냅샷에서 요소를 추출하지 못했습니다. AI 분석에 영향을 줄 수 있습니다."
        );
      } else {
        console.log(`🔍 스냅샷에서 ${elements.length}개 요소 추출 완료`);
      }

      // 최종 스냅샷 데이터 구성
      const snapshotData = {
        url,
        title,
        timestamp: new Date().toISOString(),
        elements,
        rawSnapshot: rawSnapshotText, // 원본 스냅샷 텍스트도 저장 (AI 분석 참고용)
      };

      return JSON.stringify(snapshotData, null, 2);
    } catch (error) {
      console.error("❌ 페이지 스냅샷 가져오기 실패:", error);
      return JSON.stringify(
        {
          url: "unknown",
          title: "unknown",
          timestamp: new Date().toISOString(),
          elements: [],
          error: error instanceof Error ? error.message : String(error),
        },
        null,
        2
      );
    }
  }

  private async getElementInfoFromAI(
    step: TestStep,
    snapshot: string
  ): Promise<{ selector?: string; ref?: string | null } | null> {
    try {
      console.log("🧠 AI에게 스냅샷 분석 요청중...");
      // console.log(snapshot);

      const response = await this.anthropic.messages.create({
        model: "claude-3-5-haiku-20241022",
        // model: 'claude-3-haiku-20240307',
        max_tokens: 500,
        messages: [
          {
            role: "user",
            content: `현재 웹 페이지 정보를 JSON 형식으로 제공합니다.

- 실행할 작업: ${step.action}
- 작업 설명: ${step.description}
- 대상 요소 (지정된 selector): ${step.target || "(없음)"}
${step.value ? `- 입력할 값: ${step.value}` : ""}

다음은 페이지 구조 정보입니다. elements 배열을 참고해 가장 적합한 요소를 찾아주세요:

\`\`\`json
${snapshot}
\`\`\`

아래 형식에 따라 결과를 JSON으로만 응답해주세요:
\`\`\`json
{
  "selector": "가장 적합한 선택자 (img, link.. 등등)",
  "ref": "요소 인덱스 기반 참조 ([ref=e5]라면 e5)",
  "confidence": 0.9,
  "reasoning": "이 요소를 선택한 이유"
}
\`\`\``,
          },
        ],
      });

      try {
        const content = response.content[0];
        if (content.type === "text") {
          console.log("[📨 AI 응답 수신]");

          // JSON 추출
          const jsonMatch = content.text.match(
            /```json\s*([\s\S]*?)\s*```|(\{.*\})/s
          );
          if (jsonMatch) {
            const jsonStr = jsonMatch[1] || jsonMatch[2];
            const parsed = JSON.parse(jsonStr);

            console.log(
              `✅ AI 분석 결과: 선택자=${parsed.selector}, ref=${parsed.ref}, 신뢰도=${parsed.confidence}`
            );

            // 낮은 신뢰도 경고
            if (parsed.confidence < 0.7) {
              console.warn(
                `⚠️ 요소 찾기 신뢰도 낮음 (${parsed.confidence}): ${parsed.reasoning}`
              );
            }

            return {
              selector: parsed.selector || "",
              ref: parsed.confidence >= 0.5 ? parsed.ref : null,
            };
          }
        }
      } catch (err) {
        console.error("❌ AI 응답 파싱 실패:", err);
      }

      return null;
    } catch (error) {
      console.error("❌ AI 요소 분석 실패:", error);
      return null;
    }
  }

  private async getAIComment(
    step: TestStep,
    stepResult: StepResult
  ): Promise<string> {
    try {
      const response = await this.anthropic.messages.create({
        model: "claude-3-5-sonnet-20241022",
        max_tokens: 2000,
        messages: [
          {
            role: "user",
            content: `테스트 단계를 분석해주세요:
            - 단계 설명: ${step.description}
            - 액션: ${step.action}
            - 대상: ${step.target || step.value}
            - 결과: ${stepResult.status}
            ${stepResult.error ? `- 에러: ${stepResult.error}` : ""}

            이 단계의 실행 결과에 대해 간단히 평가해주세요. 실패한 경우 개선 방안을 제시해주세요.`,
          },
        ],
      });

      const content = response.content[0];
      if (content.type === "text") {
        return content.text;
      }
      return "평가를 생성할 수 없습니다.";
    } catch (error) {
      console.error("AI 코멘트 생성 실패:", error);
      return "평가를 생성할 수 없습니다.";
    }
  }

  private async getFinalTestComment(): Promise<string> {
    try {
      const response = await this.anthropic.messages.create({
        model: "claude-3-5-sonnet-20241022",
        max_tokens: 2000,
        messages: [
          {
            role: "user",
            content: `전체 테스트 결과를 분석해주세요:
            - 총 단계: ${this.testReport.totalSteps}
            - 성공: ${this.testReport.passedSteps}
            - 실패: ${this.testReport.failedSteps}
            - 실행 시간: ${this.testReport.duration}ms

각 단계:
${this.testReport.steps
  .map((step, i) => `${i + 1}. ${step.step.description} - ${step.status}`)
  .join("\n")}

전체 테스트에 대한 종합적인 평가와 개선 사항을 제시해주세요.`,
          },
        ],
      });

      const content = response.content[0];
      if (content.type === "text") {
        return content.text;
      }
      return "평가를 생성할 수 없습니다.";
    } catch (error) {
      console.error("최종 평가 생성 실패:", error);
      return "평가를 생성할 수 없습니다.";
    }
  }

  private async generatePlaywrightReport(): Promise<string> {
    const htmlReportDir = path.join(this.testRunDir);
    await fs.mkdir(htmlReportDir, { recursive: true });

    console.log(`HTML 리포트 생성 위치: ${htmlReportDir}`);

    try {
      // 테스트 실행 결과를 HTML 파일로 변환
      const stepsHtml = this.testReport.steps
        .map((step, index) => {
          const statusClass = step.status === "success" ? "success" : "failure";
          const screenshotHtml = step.screenshot
            ? `<div class="screenshot"><img src="screenshot?build=${
                  path.basename(
                      this.testRunDir
                  )
              }&scenario=1&file=${path.basename(
                step.screenshot
              )}" alt="Screenshot" width="800" /></div>`
            : "";

          return `
          <div class="test-step ${statusClass}">
            <h3>Step ${index + 1}: ${step.step.description}</h3>
            <div class="step-details">
              <p><strong>Action:</strong> ${step.step.action}</p>
              <p><strong>Target:</strong> ${step.step.target || "N/A"}</p>
              <p><strong>Value:</strong> ${step.step.value || "N/A"}</p>
              <p><strong>Status:</strong> ${step.status}</p>
              <p><strong>Duration:</strong> ${step.duration}ms</p>
              ${
                step.error
                  ? `<p class="error"><strong>Error:</strong> ${step.error}</p>`
                  : ""
              }
            </div>
            ${screenshotHtml}
            <div class="ai-comment">
              <h4>AI Analysis:</h4>
              <p>${step.aiComment || "No analysis available"}</p>
            </div>
          </div>
        `;
        })
        .join("");

      // HTML 템플릿 생성
      const htmlTemplate = `
      <!DOCTYPE html>
      <html lang="ko">
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>${this.testReport.testName} - 테스트 결과</title>
        <style>
          body {
            font-family: Arial, sans-serif;
            line-height: 1.6;
            color: #333;
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
          }
          h1, h2, h3 {
            color: #2c3e50;
          }
          .test-summary {
            background-color: #f8f9fa;
            border-radius: 5px;
            padding: 15px;
            margin-bottom: 30px;
          }
          .test-step {
            border: 1px solid #ddd;
            border-radius: 5px;
            padding: 20px;
            margin-bottom: 20px;
          }
          .success {
            border-left: 5px solid #28a745;
          }
          .failure {
            border-left: 5px solid #dc3545;
          }
          .step-details {
            margin-bottom: 15px;
          }
          .screenshot {
            margin: 15px 0;
            text-align: center;
          }
          .screenshot img {
            max-width: 100%;
            border: 1px solid #ddd;
            box-shadow: 0 0 10px rgba(0,0,0,0.1);
          }
          .ai-comment {
            background-color: #f0f7ff;
            padding: 15px;
            border-radius: 5px;
            margin-top: 15px;
          }
          .error {
            color: #dc3545;
          }
          table {
            width: 100%;
            border-collapse: collapse;
          }
          table, th, td {
            border: 1px solid #ddd;
            padding: 8px;
          }
          th {
            background-color: #f2f2f2;
            text-align: left;
          }
          tr:nth-child(even) {
            background-color: #f9f9f9;
          }
        </style>
      </head>
      <body>
        <h1>${this.testReport.testName}</h1>
        
        <div class="test-summary">
          <h2>테스트 요약</h2>
          <table>
            <tr>
              <th>시작 시간</th>
              <td>${new Date(this.testReport.startTime).toLocaleString()}</td>
            </tr>
            <tr>
              <th>종료 시간</th>
              <td>${new Date(this.testReport.endTime).toLocaleString()}</td>
            </tr>
            <tr>
              <th>실행 시간</th>
              <td>${this.testReport.duration}ms</td>
            </tr>
            <tr>
              <th>총 단계</th>
              <td>${this.testReport.totalSteps}</td>
            </tr>
            <tr>
              <th>성공</th>
              <td>${this.testReport.passedSteps}</td>
            </tr>
            <tr>
              <th>실패</th>
              <td>${this.testReport.failedSteps}</td>
            </tr>
          </table>
          
          <h3>최종 분석</h3>
          <div class="ai-comment">
            <p>${this.testReport.finalComment || "분석 정보가 없습니다."}</p>
          </div>
        </div>
        
        <h2>테스트 단계</h2>
        <div class="test-steps">
          ${stepsHtml}
        </div>
      </body>
      </html>
      `;

      // HTML 파일 저장
      const htmlFilePath = path.join(htmlReportDir, "report.html");
      await fs.writeFile(htmlFilePath, htmlTemplate);

      return htmlFilePath;
    } catch (error) {
      console.error("HTML 리포트 생성 실패:", error);
      return `HTML 리포트 생성 실패: ${error}`;
    }
  }
}
