import Anthropic from '@anthropic-ai/sdk';
import * as dotenv from 'dotenv';

dotenv.config();

export interface TestStep {
  action: string;
  target?: string;
  value?: string;
  description: string;
}

export class ScenarioParser {
  private anthropic: Anthropic;

  constructor() {
    this.anthropic = new Anthropic({
      apiKey: process.env.ANTHROPIC_API_KEY,
    });
  }

  async parseScenario(scenarioText: string): Promise<TestStep[]> {
    const response = await this.anthropic.messages.create({
      model: 'claude-3-5-haiku-20241022', // 모델 이름 변경
      max_tokens: 1000,
      messages: [
        {
          role: 'user',
          content: `다음 테스트 시나리오를 분석하여 구체적인 테스트 단계로 변환해주세요.
JSON 배열 형식으로, 다른 설명 없이 이 형식으로만 응답해주세요:

{
  "action": "액션 타입 (navigate, click, fill, etc.)",
  "target": "대상 요소 선택자",
  "value": "입력값 (필요한 경우)",
  "description": "단계 설명"
}

시나리오:
${scenarioText}

JSON 배열 형식으로 응답해주세요.`,
        },
      ],
    });

    try {
      const content = response.content[0];
      if (content.type === 'text') {
        const steps = JSON.parse(content.text);
        return steps;
      } else {
        throw new Error('Unexpected response type from Claude');
      }
    } catch (error) {
      console.error('Failed to parse response:', error);
      throw error;
    }
  }
}
