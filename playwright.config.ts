import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for MarbleGame E2E tests.
 *
 * The server is started with POSTHOG_ENABLED=false to prevent
 * analytics from being triggered during tests.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  /* Start the dev server before running tests */
  webServer: {
    command: './gradlew run',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120000, // 2 minutes for Gradle to compile and start
    env: {
      POSTHOG_ENABLED: 'false',
    },
  },
});
