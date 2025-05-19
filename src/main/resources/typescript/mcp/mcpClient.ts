import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { spawn, exec as execCallback } from "child_process";
import * as fs from "fs/promises";
import * as path from "path";
import { ChildProcess } from "child_process";
import { promisify } from "util";
import * as os from "os";
import { existsSync } from "fs";
import { execSync } from "child_process";

const exec = promisify(execCallback);

// 로그 레벨 정의
enum LogLevel {
  NONE = 0,
  ERROR = 1,
  WARN = 2,
  INFO = 3,
  DEBUG = 4,
}

// 현재 로그 레벨 설정 (원하는 수준으로 조정)
const CURRENT_LOG_LEVEL = LogLevel.INFO;

// 로그 파일 경로
const LOG_FILE_PATH = path.join(
  process.cwd(),
  "mcp-logs",
  `mcp-${new Date().toISOString().replace(/[:.]/g, "-")}.log`
);

// 로그 디렉토리 생성 함수
async function ensureLogDirectory() {
  try {
    await fs.mkdir(path.dirname(LOG_FILE_PATH), { recursive: true });
  } catch (error) {
    console.error("로그 디렉토리 생성 실패:", error);
  }
}

// 로그 출력 함수
async function log(level: LogLevel, ...args: any[]) {
  const message = args
    .map((arg) =>
      typeof arg === "object" ? JSON.stringify(arg, null, 2) : String(arg)
    )
    .join(" ");

  const timestamp = new Date().toISOString();
  const levelName = LogLevel[level];
  const formattedMessage = `[${timestamp}] [${levelName}] ${message}\n`;

  // 로그 파일에 기록
  try {
    await fs.appendFile(LOG_FILE_PATH, formattedMessage);
  } catch (error) {
    // 에러 레벨 메시지는 항상 콘솔에 출력
    if (level === LogLevel.ERROR) {
      console.error("로그 파일 기록 실패:", error);
    }
  }

  // 콘솔에 출력 (로그 레벨에 따라)
  if (level <= CURRENT_LOG_LEVEL) {
    if (level === LogLevel.ERROR) {
      console.error(...args);
    } else if (level === LogLevel.WARN) {
      console.warn(...args);
    } else {
      console.log(...args);
    }
  }
}

type ContentItem = {
  type: string;
  text?: string;
  data?: string;
  mimeType?: string;
  [key: string]: any;
};

// 도구 결과의 인터페이스 정의
interface ToolResult {
  isError?: boolean;
  content?: ContentItem[];
  // 다른 일반적인 속성들
  binary?: string;
  url?: string;
  title?: string;
  result?: any;
  visible?: boolean;
  [key: string]: any;
}

const TOOL_MAPPING: Record<string, string> = {
  // 브라우저 초기화 관련
  browserLaunch: "browser_install", // 설치용
  browserNewContext: "browser_snapshot", // snapshot을 처음 찍는 용도로 사용 가능
  contextNewPage: "browser_tab_new",

  // 페이지 관련
  pageGoto: "browser_navigate",
  pageClick: "browser_click",
  pageFill: "browser_type",
  pagePress: "browser_press_key",
  pageWaitForLoadState: "browser_wait_for",
  pageWaitForSelector: "browser_snapshot", // 직접 wait 기능 없으므로 snapshot으로 대체
  pageEvaluate: "browser_snapshot", // 별도 evaluate tool 없음
  pageUrl: "browser_snapshot",
  pageTitle: "browser_snapshot",
  pageIsVisible: "browser_snapshot",

  // 기타
  pageScreenshot: "browser_take_screenshot",
  pageSnapshot: "browser_snapshot",
  pageClose: "browser_tab_close",
  contextClose: "browser_close",
  handleDialog: "browser_handle_dialog",
};

// 도구 이름을 실제 MCP 도구 이름으로 변환하는 함수
function mapToolName(name: string): string {
  return TOOL_MAPPING[name] || name;
}

// 도구 인자를 실제 MCP 도구 인자 형식으로 변환하는 함수
function mapToolArgs(name: string, args: any): any {
  let mappedArgs: any = {};

  switch (name) {
    case "browserLaunch":
      mappedArgs = {};
      break;

    case "browserNewContext":
    case "contextNewPage":
      mappedArgs = {
        incognito: true,
      };
      break;

    case "pageGoto":
      mappedArgs = {
        url: args.url,
      };
      break;

    case "pageWaitForLoadState":
      mappedArgs = {
        time: args.timeout ? Math.min(args.timeout / 1000, 10) : 0.5,
      };
      break;

    case "pageClick":
      mappedArgs = {
        ref: args.ref,
        element: args.element,
      };
      break;

    case "pageFill":
      mappedArgs = {
        element: args.element,
        ref: args.ref,
        text: args.text,
        submit: false, // Enter 키를 누르지 않음
      };
      break;

    case "pagePress":
      mappedArgs = {
        key: args.key,
      };
      break;

    case "pageScreenshot":

    case "pageWaitForSelector":
      // browser_wait로 대체
      mappedArgs = {
        time: 1, // 초단위
      };
      break;

    case "pageIsVisible":
      mappedArgs = {};
      break;

    case "pageClose":
    case "contextClose":
      mappedArgs = {};
      break;

    default:
      mappedArgs = args;
      break;
  }

  return mappedArgs;
}

function transformResult(name: string, result: any): ToolResult {
  if (result.isError) {
    log(LogLevel.ERROR, `Tool execution error for ${name}:`, result);
    return result as ToolResult;
  }

  const toolResult: ToolResult = {};

  // content에 텍스트가 있다면 먼저 파싱
  const textContent =
    result.content?.find((item: any) => item.type === "text")?.text || "";

  switch (name) {
    case "pageUrl": {
      const urlMatch = textContent.match(/- Page URL: (.+)/);
      if (urlMatch) {
        toolResult.url = urlMatch[1].trim();
      }
      break;
    }

    case "pageTitle": {
      const titleMatch = textContent.match(/- Page Title: (.+)/);
      if (titleMatch) {
        toolResult.title = titleMatch[1].trim();
      }
      break;
    }

    case "pageSnapshot": {
      toolResult.pageSnapshot = textContent;
      break;
    }

    case "pageIsVisible": {
      // 요소 ref나 텍스트가 있는지 확인해 존재 여부 판단
      const targetRef = "회원가입"; // 예시 - 실제로는 매개변수나 외부에서 받아야 함
      toolResult.visible = textContent.includes(targetRef);
      break;
    }

    case "pageEvaluate": {
      // 요소 리스트를 추출하는 로직 예시
      const matches = [...textContent.matchAll(/- ([^\n]+) \[ref=([^\]]+)\]/g)];
      toolResult.result = matches.map((m) => ({
        label: m[1].trim(),
        ref: m[2].trim(),
      }));
      break;
    }

    case "pageScreenshot": {
      if (result.screenshot) {
        toolResult.binary = result.screenshot;
      } else if (result.data) {
        toolResult.binary = result.data;
      } else if (result.content && Array.isArray(result.content)) {
        const imageContent = result.content.find(
          (item: any) => item.type === "image" && item.data
        );
        if (imageContent?.data) {
          toolResult.binary = imageContent.data;
        }
      }
      break;
    }

    default: {
      Object.assign(toolResult, result);
      break;
    }
  }

  // content 복사
  if (result.content) {
    toolResult.content = result.content;
  }

  return toolResult;
}

export class MCPClient {
  private client: Client;
  private transport: StdioClientTransport | undefined;
  private toolCache: Record<string, any> = {}; // 도구 캐시
  private mcpProcess: ChildProcess | null = null;

  constructor() {
    // 로그 디렉토리 생성
    ensureLogDirectory();

    this.client = new Client(
      {
        name: "natural-language-testing",
        version: "1.0.0",
      },
      {
        capabilities: {},
      }
    );

    log(LogLevel.INFO, "MCPClient 인스턴스가 생성되었습니다.");
  }

  // 운영체제 감지 및 관련 유틸리티 함수
  private async detectAndSetupEnvironment(): Promise<void> {
    // 운영체제 확인
    const platform = os.platform();
    log(LogLevel.INFO, `운영체제 감지: ${platform}`);
    console.log(`운영체제 감지: ${platform}`);

    // WSL 환경인지 확인 (Linux에서 WSL_DISTRO_NAME 환경 변수가 있으면 WSL)
    const isWSL = platform === "linux" && !!process.env.WSL_DISTRO_NAME;
    if (isWSL) {
      log(LogLevel.INFO, `WSL 환경 감지됨: ${process.env.WSL_DISTRO_NAME}`);
      console.log(`WSL 환경 감지됨: ${process.env.WSL_DISTRO_NAME}`);
    }

    // 환경에 따른 처리
    if (platform === "win32" || platform.includes("mingw")) {
      // Windows 환경 처리
      await this.setupWindowsEnvironment();
    } else if (isWSL) {
      // WSL 환경 처리
      await this.setupWSLEnvironment();
    } else if (platform === "linux") {
      // 순수 Linux 환경 처리
      await this.setupLinuxEnvironment();
    } else if (platform === "darwin") {
      // macOS 환경 처리
      await this.setupMacOSEnvironment();
    } else {
      log(LogLevel.WARN, `지원되지 않는 운영체제: ${platform}`);
      console.warn(
        `지원되지 않는 운영체제(${platform})입니다. 일부 기능이 제한될 수 있습니다.`
      );
    }
  }

  // Windows 환경 설정
  private async setupWindowsEnvironment(): Promise<void> {
    log(LogLevel.INFO, "Windows 환경 설정 적용 중...");

    try {
      // Windows 코드 페이지를 UTF-8로 설정 (한글 지원)
      try {
        execSync("chcp 65001", { encoding: "utf8" });
        log(LogLevel.INFO, "Windows 코드 페이지를 UTF-8(65001)로 설정했습니다");
      } catch (error) {
        log(LogLevel.WARN, "Windows 코드 페이지 설정 실패:", error);
      }

      // Windows에서 Chrome 브라우저 경로 확인
      const chromePaths = [
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe`,
      ];

      let chromePath = "";
      for (const path of chromePaths) {
        if (existsSync(path)) {
          chromePath = path;
          log(LogLevel.INFO, `Windows에서 Chrome 발견: ${path}`);
          console.log(`Chrome 브라우저 발견: ${path}`);
          break;
        }
      }

      // Chrome이 없으면 설치 안내
      if (!chromePath) {
        log(LogLevel.WARN, "Windows 환경에서 Chrome을 찾을 수 없습니다.");
        console.warn(
          "Chrome 브라우저를 찾을 수 없습니다. https://www.google.com/chrome/ 에서 설치하세요."
        );
        // Windows에서는 Chrome 자동 설치를 지원하지 않으므로 경고만 표시
      }

      log(LogLevel.INFO, "Windows 환경 설정 완료");
    } catch (error) {
      log(LogLevel.ERROR, "Windows 환경 설정 실패:", error);
      console.error("Windows 환경 설정 중 오류가 발생했습니다.");
    }
  }

  // WSL 환경 설정
  private async setupWSLEnvironment(): Promise<void> {
    log(LogLevel.INFO, "WSL 환경 설정 적용 중...");

    try {
      // WSL에서 필요한 설정
      if (!process.env.DISPLAY) {
        log(LogLevel.WARN, "WSL에서 DISPLAY 환경 변수가 설정되지 않았습니다.");
        console.warn(
          "WSL에서 GUI 애플리케이션을 실행하려면 DISPLAY 환경 변수가 필요할 수 있습니다."
        );
      }

      // Linux 환경 설정 호출 (공통 부분)
      await this.setupLinuxEnvironment();

      log(LogLevel.INFO, "WSL 환경 설정 완료");
    } catch (error) {
      log(LogLevel.ERROR, "WSL 환경 설정 실패:", error);
      console.error("WSL 환경 설정 중 오류가 발생했습니다.");
    }
  }

  // Linux 환경 설정
  private async setupLinuxEnvironment(): Promise<void> {
    log(LogLevel.INFO, "Linux 환경 설정 적용 중...");

    try {
      // 한글 로케일 설정
      try {
        // 현재 로케일 확인
        const { stdout: currentLocale } = await exec("locale");
        log(LogLevel.INFO, `현재 시스템 로케일: ${currentLocale}`);
        console.log(`현재 시스템 로케일: ${currentLocale}`);

        // 한글 로케일 설정
        process.env.LANG = "ko_KR.UTF-8";
        process.env.LC_ALL = "ko_KR.UTF-8";
        log(LogLevel.INFO, "한글 로케일 환경 변수 설정 완료");
        console.log("한글 로케일 환경 변수 설정 완료");

        // 한글 폰트 및 로케일 설치 - 필요시 시도
        await this.installKoreanFonts().catch((error) => {
          log(LogLevel.WARN, "한글 폰트 설치 실패 (무시하고 계속):", error);
        });
      } catch (error) {
        log(LogLevel.WARN, "한글 로케일 설정 중 오류 (무시하고 계속):", error);
      }

      // Chrome 브라우저 설치 확인 및 설치
      const isChromiumInstalled = await this.isChromeBrowserInstalled();
      if (!isChromiumInstalled) {
        log(
          LogLevel.INFO,
          "Chrome/Chromium이 설치되어 있지 않습니다. 설치를 시도합니다..."
        );
        console.log(
          "Chrome/Chromium이 설치되어 있지 않습니다. 설치를 시도합니다..."
        );

        const installSuccess = await this.installChromeBrowser().catch(
          () => false
        );
        if (!installSuccess) {
          log(
            LogLevel.WARN,
            "Chrome/Chromium 설치에 실패했지만 계속 진행합니다."
          );
          console.warn(
            "Chrome/Chromium 설치에 실패했지만 계속 진행을 시도합니다."
          );
        }
      } else {
        log(LogLevel.INFO, "Chrome/Chromium이 이미 설치되어 있습니다.");
        console.log("Chrome/Chromium이 이미 설치되어 있습니다.");
      }

      log(LogLevel.INFO, "Linux 환경 설정 완료");
    } catch (error) {
      log(LogLevel.ERROR, "Linux 환경 설정 실패:", error);
      console.error("Linux 환경 설정 중 오류가 발생했습니다.");
    }
  }

  // macOS 환경 설정
  private async setupMacOSEnvironment(): Promise<void> {
    log(LogLevel.INFO, "macOS 환경 설정 적용 중...");

    try {
      // macOS에서 Chrome 브라우저 확인
      const chromePaths = [
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        `${os.homedir()}/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`,
      ];

      let chromePath = "";
      for (const path of chromePaths) {
        if (existsSync(path)) {
          chromePath = path;
          log(LogLevel.INFO, `macOS에서 Chrome 발견: ${path}`);
          console.log(`Chrome 브라우저 발견: ${path}`);
          break;
        }
      }

      // Chrome이 없으면 설치 안내
      if (!chromePath) {
        log(LogLevel.WARN, "macOS에서 Chrome을 찾을 수 없습니다.");
        console.warn(
          "Chrome 브라우저를 찾을 수 없습니다. https://www.google.com/chrome/ 에서 설치하세요."
        );
      }

      log(LogLevel.INFO, "macOS 환경 설정 완료");
    } catch (error) {
      log(LogLevel.ERROR, "macOS 환경 설정 실패:", error);
      console.error("macOS 환경 설정 중 오류가 발생했습니다.");
    }
  }

  // WSL Ubuntu 환경에서 Chrome 브라우저가 설치되어 있는지 확인하는 함수
  private async isChromeBrowserInstalled(): Promise<boolean> {
    try {
      // OS 감지
      const platform = os.platform();

      if (platform === "win32" || platform.includes("mingw")) {
        // Windows에서 Chrome 경로 확인
        const chromePaths = [
          "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
          "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
          `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe`,
        ];

        for (const path of chromePaths) {
          if (existsSync(path)) {
            log(LogLevel.INFO, `Windows에서 Chrome 발견: ${path}`);
            return true;
          }
        }

        // where 명령어로 확인 (Windows)
        try {
          const { stdout } = await exec("where chrome");
          if (stdout.trim()) {
            log(LogLevel.INFO, `Chrome 발견: ${stdout.trim()}`);
            return true;
          }
        } catch (error) {
          // 무시: Chrome이 없는 경우 오류 발생
        }

        return false;
      } else if (platform === "darwin") {
        // macOS에서 Chrome 경로 확인
        const macChromePaths = [
          "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
          `${os.homedir()}/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`,
        ];

        for (const path of macChromePaths) {
          if (existsSync(path)) {
            log(LogLevel.INFO, `macOS에서 Chrome 발견: ${path}`);
            return true;
          }
        }

        return false;
      } else {
        // Linux/WSL에서 Chrome 확인 (기존 코드)
        // WSL Ubuntu에서 일반적인 Chrome/Chromium 위치 확인
        const chromePaths = [
          "/usr/bin/google-chrome",
          "/usr/bin/google-chrome-stable",
          "/usr/bin/chromium-browser",
          "/usr/bin/chromium",
        ];

        // 파일 시스템 접근 확인
        for (const path of chromePaths) {
          try {
            await fs.access(path);
            log(LogLevel.INFO, `Chrome/Chromium 발견: ${path}`);
            return true;
          } catch {
            // 다른 경로 계속 확인
          }
        }

        // which 명령어로 확인
        try {
          const { stdout } = await exec(
            "which google-chrome || which chromium-browser || which chromium"
          );
          if (stdout.trim()) {
            log(LogLevel.INFO, `Chrome/Chromium 발견: ${stdout.trim()}`);
            return true;
          }
        } catch (error) {
          log(LogLevel.DEBUG, "which 명령어 실행 실패:", error);
        }

        return false;
      }
    } catch (error) {
      log(LogLevel.ERROR, "Chrome 설치 확인 중 오류 발생:", error);
      return false;
    }
  }

  // WSL Ubuntu 환경에서 Chrome 브라우저 설치 함수
  // OS에 맞는 브라우저 설치 메서드
  private async installChromeBrowser(): Promise<boolean> {
    try {
      const platform = os.platform();

      // Windows 환경
      if (platform === "win32" || platform.includes("mingw")) {
        console.log("Windows 환경에서는 Chrome 자동 설치를 지원하지 않습니다.");
        console.log(
          "Chrome을 수동으로 설치한 후 다시 시도하세요: https://www.google.com/chrome/"
        );

        // Windows에서는 자동 설치 대신 안내만 제공
        return false;
      }
      // macOS 환경
      else if (platform === "darwin") {
        console.log("macOS 환경에서는 Chrome 자동 설치를 지원하지 않습니다.");
        console.log(
          "Chrome을 수동으로 설치한 후 다시 시도하세요: https://www.google.com/chrome/"
        );

        // macOS에서는 자동 설치 대신 안내만 제공
        return false;
      }
      // Linux 또는 WSL 환경
      else {
        log(LogLevel.INFO, "Linux/WSL 환경에서 Chrome 브라우저 설치 시도...");
        console.log("Chrome 브라우저 설치 중...");

        try {
          // 리눅스 배포판 확인 (Ubuntu, Debian 등)
          const { stdout: lsbRelease } = await exec(
            "lsb_release -a 2>/dev/null || cat /etc/os-release"
          ).catch(() => ({ stdout: "" }));
          const isUbuntu = lsbRelease.toLowerCase().includes("ubuntu");
          const isDebian = lsbRelease.toLowerCase().includes("debian");

          if (isUbuntu || isDebian) {
            // apt 기반 설치 (Ubuntu/Debian)
            try {
              await exec("apt-get update");
              await exec("apt-get install -y wget apt-transport-https");

              // 구글 크롬 저장소 추가
              await exec(
                "wget -q -O - https://dl-ssl.google.com/linux/linux_signing_key.pub | apt-key add -"
              );
              await exec(
                "sh -c 'echo \"deb [arch=amd64] https://dl.google.com/linux/chrome/deb/ stable main\" > /etc/apt/sources.list.d/google-chrome.list'"
              );

              // 패키지 업데이트 및 Chrome 설치
              await exec("apt-get update");
              await exec("apt-get install -y google-chrome-stable");
            } catch (chromeError) {
              log(
                LogLevel.WARN,
                "Google Chrome 설치 실패, Chromium 설치 시도:",
                chromeError
              );

              // Chromium 브라우저 대안으로 설치
              try {
                await exec("apt-get install -y chromium-browser");
              } catch (chromiumError) {
                log(LogLevel.ERROR, "Chromium 설치 실패:", chromiumError);

                // 마지막 대안: 스냅 패키지로 Chromium 설치
                try {
                  await exec("apt-get install -y snapd");
                  await exec("snap install chromium");
                } catch (snapError) {
                  log(
                    LogLevel.ERROR,
                    "모든 브라우저 설치 시도 실패:",
                    snapError
                  );
                  return false;
                }
              }
            }
          } else {
            // 다른 리눅스 배포판의 경우
            log(
              LogLevel.WARN,
              "지원되지 않는 Linux 배포판입니다. Chrome 설치가 실패할 수 있습니다."
            );
            try {
              // 일반적인 방법으로 시도
              await exec(
                "apt-get update && apt-get install -y chromium-browser"
              );
            } catch (error) {
              log(LogLevel.ERROR, "브라우저 설치 실패:", error);
              return false;
            }
          }
        } catch (error) {
          log(LogLevel.ERROR, "Linux 환경에서 Chrome 설치 실패:", error);
          return false;
        }

        // 설치 확인
        const isInstalled = await this.isChromeBrowserInstalled();
        if (isInstalled) {
          log(LogLevel.INFO, "Chrome/Chromium 브라우저 설치 완료");
          console.log("Chrome/Chromium 브라우저 설치 완료");
          return true;
        } else {
          log(LogLevel.ERROR, "Chrome/Chromium 설치에 실패했습니다.");
          console.error("Chrome/Chromium 설치에 실패했습니다.");
          return false;
        }
      }
    } catch (error) {
      log(LogLevel.ERROR, "브라우저 설치 실패:", error);
      console.error("브라우저 설치 실패:", error);
      return false;
    }
  }

  private async installKoreanFonts(): Promise<boolean> {
    try {
      log(LogLevel.INFO, "한글 폰트 설치 확인 중...");

      // 한글 폰트 패키지 설치
      await exec("apt-get update");
      await exec("apt-get install -y fonts-nanum fonts-noto-cjk");

      // 폰트 캐시 업데이트
      await exec("fc-cache -f -v");

      log(LogLevel.INFO, "한글 폰트 설치 및 캐시 업데이트 완료");
      console.log("한글 폰트 설치 완료");
      return true;
    } catch (error) {
      log(LogLevel.ERROR, "한글 폰트 설치 실패:", error);
      console.error("한글 폰트 설치 실패:", error);
      return false;
    }
  }

  async connect(): Promise<void> {
    log(LogLevel.INFO, "MCP 서버에 연결 시도 중...");
    console.log("MCP 서버에 연결 중...");

    try {
      // 운영체제 감지 및 환경 설정
      await this.detectAndSetupEnvironment();

      // npm 명령어 경로 (Windows와 다른 플랫폼 구분)
      const npm = process.platform === "win32" ? "npx.cmd" : "npx";

      // MCP 서버 시작
      // 직접 child process를 생성
      const proc = spawn(npm, ["--no-install", "@playwright/mcp"], {
        stdio: ["pipe", "pipe", "pipe"],
        shell: true,
        env: { ...process.env },
      });
      this.mcpProcess = proc;

      // stdout과 stderr 이벤트 리스너 추가
      proc.stdout.on("data", (data) => {
        log(LogLevel.DEBUG, "MCP Server stdout:", data.toString());
      });

      proc.stderr.on("data", (data) => {
        log(LogLevel.WARN, "MCP Server stderr:", data.toString());
      });

      proc.on("error", (error) => {
        log(LogLevel.ERROR, "MCP 서버 시작 실패:", error);
      });

      proc.on("close", (code) => {
        log(LogLevel.INFO, `MCP 서버 프로세스가 코드 ${code}로 종료되었습니다`);
      });

      // 프로세스가 시작되기를 기다림
      await new Promise((resolve) => setTimeout(resolve, 2000));

      this.transport = new StdioClientTransport({
        command: npm,
        args: ["--no-install", "@playwright/mcp"],
      });

      log(LogLevel.INFO, "Transport를 통해 MCP 서버에 연결 중...");
      await this.client.connect(this.transport);

      console.log("MCP 서버에 성공적으로 연결되었습니다");
      log(LogLevel.INFO, "Playwright MCP 서버에 성공적으로 연결되었습니다");

      // 연결 후 사용 가능한 도구 목록 확인
      try {
        const tools = await this.client.listTools();
        log(LogLevel.DEBUG, "Available tools:", tools);

        // 도구 목록 캐싱
        if (tools && tools.tools) {
          this.toolCache = tools.tools.reduce(
              (acc: Record<string, any>, tool: any) => {
                acc[tool.name] = tool;
                return acc;
              },
              {}
          );

          log(
              LogLevel.INFO,
              `${tools.tools.length}개의 MCP 도구를 로드했습니다`
          );
        } else {
          log(
              LogLevel.WARN,
              "도구를 찾을 수 없거나 도구 구조가 예상과 다릅니다:",
              tools
          );
        }
      } catch (error) {
        log(LogLevel.ERROR, "도구 목록 로드 실패:", error);
      }
    } catch (error) {
      log(LogLevel.ERROR, "환경 설정 또는 MCP 서버 연결 실패:", error);
      console.error(
          "MCP 서버 연결 준비 과정에서 오류가 발생했습니다:",
          error instanceof Error ? error.message : error
      );
      throw error;
    }
  }

  async handleDialog(
      accept: boolean = true,
      promptText?: string
  ): Promise<void> {
    try {
      console.log(`🔄 대화 상자 처리 중... (${accept ? "수락" : "거부"})`);

      // browser_handle_dialog 도구 직접 호출
      await this.client.callTool({
        name: "browser_handle_dialog", // 도구 목록에 존재하는 정확한 이름
        arguments: {
          accept,
          promptText, // 선택적으로 제공
        },
      });

      console.log("✅ 대화 상자 처리 완료");
    } catch (error) {
      console.error("❌ 대화 상자 처리 실패:", error);

      // 실패하더라도 예외를 던지지 않고 로그만 남김
      // 간혹 대화 상자가 이미 닫혔거나 다른 이유로 오류가 발생할 수 있음
      log(LogLevel.WARN, "대화 상자 처리 오류가 발생했으나 계속 진행합니다");
    }
  }

  async executeAction(action: string, args: any): Promise<ToolResult> {
    const mappedAction = mapToolName(action);
    const mappedArgs = mapToolArgs(action, args);

    log(LogLevel.DEBUG, `액션 실행: ${action} (${mappedAction})`, mappedArgs);
    console.log(`액션 실행: ${action} (${mappedAction})`);

    // 도구 존재 확인
    if (!this.toolCache[mappedAction]) {
      const errorMsg = `도구 "${mappedAction}"가 사용 가능한 도구에 없습니다.`;
      log(LogLevel.ERROR, errorMsg);
      throw new Error(errorMsg);
    }

    // 선제적으로 대화 상자가 나타날 가능성이 있는 액션 리스트
    const actionsThatMightShowDialog = [
      "pageClick",
      "pageFill",
      "pagePress",
      "pageGoto",
    ];

    try {
      // MCP 클라이언트 호출
      // console.log(mappedAction);

      // 결과를 저장할 변수를 미리 선언 (타입 충돌 방지)
      let result: any;

      // 선제적 대화 상자 처리 설정 (click, fill, press 등 상호작용 액션의 경우)
      if (actionsThatMightShowDialog.includes(action)) {
        log(
            LogLevel.INFO,
            `${action} 액션이 대화 상자를 표시할 수 있어 타임아웃 로직을 적용합니다.`
        );

        // 1. 타임아웃 Promise 설정 (5초)
        let completed = false;
        const timeoutPromise = new Promise<any>((_, reject) => {
          setTimeout(() => {
            if (!completed) {
              log(
                  LogLevel.WARN,
                  "도구 호출 타임아웃. 대화 상자가 활성화되었을 수 있습니다."
              );
              reject(new Error("도구 호출 타임아웃."));
            }
          }, 5000);
        });

        // 2. 실제 도구 호출 Promise
        const toolCallPromise = new Promise<any>(async (resolve) => {
          try {
            const toolResult = await this.client.callTool({
              name: mappedAction,
              arguments: mappedArgs,
            });
            completed = true;
            resolve(toolResult);
          } catch (error) {
            completed = true;
            throw error;
          }
        });

        // 3. Promise.race로 어느 것이 먼저 끝나는지 확인
        try {
          result = await Promise.race([toolCallPromise, timeoutPromise]);
        } catch (error) {
          // 타임아웃이나 오류 발생 시 대화 상자 처리 시도
          log(
              LogLevel.WARN,
              "액션 실행 중 타임아웃 또는 오류 발생. 대화 상자 처리 시도:",
              error
          );

          try {
            // 브라우저 대화 상자 처리 시도
            await this.client.callTool({
              name: "browser_handle_dialog",
              arguments: {
                accept: true,
              },
            });

            log(LogLevel.INFO, "대화 상자 처리 완료. 액션을 계속 진행합니다.");

            // 대화 상자 처리 후 원래 액션 결과 반환 (이미 수행된 작업의 영향으로 상태가 변경되었을 수 있음)
            return {
              content: [
                {
                  type: "text",
                  text: "대화 상자 처리 완료 후 계속 진행",
                },
              ],
            };
          } catch (dialogError) {
            log(LogLevel.ERROR, "대화 상자 처리 실패:", dialogError);
            // 대화 상자 처리에 실패해도 계속 진행 (원래 에러 다시 발생시킴)
            throw error;
          }
        }
      } else {
        // 대화 상자를 발생시킬 가능성이 낮은 액션인 경우 일반적으로 처리
        result = await this.client.callTool({
          name: mappedAction,
          arguments: mappedArgs,
        });
      }

      // 디버그 정보 로깅
      if (action === "pageSnapshot") {
        log(LogLevel.INFO, `스냅샷 응답 (${mappedAction}):`, result);
        console.log(`스냅샷 결과:`, result);
      } else {
        log(LogLevel.DEBUG, `도구 응답 (${mappedAction}):`, result);
      }

      // 결과가 오류인 경우 예외 발생
      if (result.isError) {
        const errorMsg = `도구 "${mappedAction}" 실행 실패: ${JSON.stringify(
            result
        )}`;
        log(LogLevel.ERROR, errorMsg);
        throw new Error(errorMsg);
      }

      // 액션 완료 메시지
      console.log(`액션 ${action} 완료`);

      // 결과 변환 및 반환
      const transformedResult = transformResult(action, result);
      return transformedResult;
    } catch (error) {
      const errMsg =
          typeof error === "string"
              ? error
              : error instanceof Error
                  ? error.message
                  : JSON.stringify(error);

      // 모달 대화 상자 감지 로직 개선 - 더 많은 패턴 추가
      const maybeModal =
          errMsg.includes("does not handle the modal state") ||
          errMsg.includes('can be handled by the "browser_handle_dialog" tool') ||
          errMsg.includes("dialog") ||
          errMsg.includes("timeout") ||
          errMsg.includes("타임아웃") ||
          errMsg.includes("alert") ||
          errMsg.includes("confirm") ||
          errMsg.includes("prompt");

      if (maybeModal) {
        console.warn("⚠️ Modal dialog 감지됨. 자동 처리 시도...");

        try {
          // 대화 상자 처리 - 도구 목록에 맞게 직접 호출
          await this.client.callTool({
            name: "browser_handle_dialog",
            arguments: {
              accept: true,
            },
          });

          console.log("✅ 대화 상자 처리 완료");

          // 대화 상자 처리 후 잠시 대기 (페이지 상태 안정화)
          await new Promise((resolve) => setTimeout(resolve, 1000));

          // 액션에 따라 처리 방법 결정
          if (actionsThatMightShowDialog.includes(action)) {
            // 이미 액션이 수행되었을 수 있으므로 다시 시도하지 않고 성공으로 간주
            return {
              content: [
                {
                  type: "text",
                  text: "대화 상자 처리 완료 (성공)",
                },
              ],
            };
          } else {
            // 다른 유형의 액션인 경우 다시 시도
            console.log("대화 상자 처리 후 액션 재시도 중...");
            return await this.executeAction(action, args);
          }
        } catch (dialogErr) {
          // 대화 상자 처리 실패 시 원래 오류 외에 추가 정보 기록
          log(LogLevel.ERROR, "❌ 대화 상자 처리 실패:", dialogErr);
          console.error("❌ 대화 상자 처리 실패:", dialogErr);

          // 에러를 throw하는 대신 가능한 경우 계속 진행
          return {
            content: [
              {
                type: "text",
                text: "대화 상자 처리 시도 후 계속 진행",
              },
            ],
          };
        }
      }

      // 일반적인 오류 처리
      log(LogLevel.ERROR, `액션 실행 오류 ${action} (${mappedAction}):`, error);
      console.error(
          `액션 ${action} 실행 오류:`,
          error instanceof Error ? error.message : error
      );
      throw error;
    }
  }

  async disconnect(): Promise<void> {
    log(LogLevel.INFO, "MCP 서버 연결 해제 중...");

    // 콘솔에 메시지 표시
    console.log("MCP 서버 연결 해제 중...");

    try {
      await this.client.close();
      log(LogLevel.INFO, "MCP 서버 연결이 해제되었습니다");

      // 콘솔에 메시지 표시
      console.log("MCP 서버 연결이 해제되었습니다");
    } catch (error) {
      log(LogLevel.ERROR, "연결 해제 중 오류 발생:", error);

      // 콘솔에 오류 표시
      console.error("연결 해제 중 오류 발생:", error);

      if (this.mcpProcess) {
        log(LogLevel.INFO, "MCP 서버 프로세스 종료 시도...");
        this.mcpProcess.kill(); // soft kill
        this.mcpProcess = null;
        log(LogLevel.INFO, "브라우저 및 MCP 클라이언트 정리 완료");
        console.log("브라우저 및 MCP 클라이언트 정리 완료");
      }
    }
    process.exit(0); // 완전 종료
  }

}
