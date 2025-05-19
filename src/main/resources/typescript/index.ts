import * as fs from 'fs/promises';
import { ScenarioParser } from './parser/scenarioParser';
import { AdaptivePlaywrightExecutor } from './executor/adaptivePlaywrightExecutor';

async function main() {
  try {
    const scenarioPath = process.argv[2];
    if (!scenarioPath) {
      console.error('Usage: npm start <scenario-file>');
      process.exit(1);
    }

    const scenarioText = await fs.readFile(scenarioPath, 'utf-8');

    // 시나리오 파싱
    const parser = new ScenarioParser();
    console.log('Parsing scenario...');
    const steps = await parser.parseScenario(scenarioText);

    console.log('Parsed steps:', JSON.stringify(steps, null, 2));

    // 적응형 Playwright 테스트 실행
    const executor = new AdaptivePlaywrightExecutor();
    await executor.initialize();

    try {
      await executor.executeSteps(steps);
    } catch (error) {
      console.error('Test execution failed:', error);
    } finally {
      await executor.cleanup();
    }

    console.log('Test completed!');
  } catch (error) {
    console.error('Error:', error);
    process.exit(1);
  }
}

main();
