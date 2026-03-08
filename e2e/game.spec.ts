import { test, expect } from '@playwright/test';

/**
 * E2E tests for the MarbleGame application.
 */

test.describe('Homepage', () => {
  const marblesForm = (page) => page.locator('#create-form-marbles');

  test('should load the homepage', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveTitle(/Games/);
    await expect(page.locator('h1')).toContainText('Games');
  });

  test('should not include PostHog script when analytics disabled', async ({ page }) => {
    await page.goto('/');

    const posthogScript = await page.evaluate(() => {
      return typeof (window as any).posthog !== 'undefined';
    });
    expect(posthogScript).toBe(false);
  });

  test('should not show cookie banner when analytics disabled', async ({ page }) => {
    await page.goto('/');

    const cookieBanner = page.locator('#cookie-banner');
    await expect(cookieBanner).toHaveCount(0);
  });

  test('should have create marbles form visible by default', async ({ page }) => {
    await page.goto('/');

    await expect(marblesForm(page).locator('input[name="playerName"]')).toBeVisible();
    await expect(marblesForm(page).getByRole('button', { name: 'Create Marbles' })).toBeVisible();
  });

  test('player name input should be required', async ({ page }) => {
    await page.goto('/');

    const input = marblesForm(page).locator('input[name="playerName"]');
    await expect(input).toHaveAttribute('required');
  });
});

test.describe('Game Creation', () => {
  const marblesForm = (page) => page.locator('#create-form-marbles');

  test('should create a new game', async ({ page }) => {
    await page.goto('/');

    await marblesForm(page).locator('input[name="playerName"]').fill('TestPlayer');
    await marblesForm(page).getByRole('button', { name: 'Create Marbles' }).click();

    await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
    await expect(page.locator('h1')).toContainText('Marble Game');
  });

  test('should preserve player name in game', async ({ page }) => {
    await page.goto('/');

    const playerName = 'E2ETestPlayer';
    await marblesForm(page).locator('input[name="playerName"]').fill(playerName);
    await marblesForm(page).getByRole('button', { name: 'Create Marbles' }).click();

    await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
    await expect(page.getByText(playerName)).toBeVisible();
  });
});

test.describe('Game Joining', () => {
  test('should show 404 page for non-existent game', async ({ page }) => {
    const response = await page.goto('/game/00000000');

    expect(response?.status()).toBe(404);
    await expect(page.locator('.card h1')).toBeVisible();
    await expect(page.locator('.card a.btn')).toBeVisible();
  });
});

test.describe('Static Pages', () => {
  test('should load imprint page', async ({ page }) => {
    await page.goto('/imprint');

    await expect(page.locator('h1')).toBeVisible();
    await expect(page.locator('.card')).toBeVisible();
  });

  test('should load privacy page', async ({ page }) => {
    await page.goto('/privacy');

    await expect(page.locator('h1')).toBeVisible();
    await expect(page.locator('.card')).toBeVisible();
  });

  test('should have footer links', async ({ page }) => {
    await page.goto('/');

    await expect(page.locator('footer a[href="/imprint"]')).toBeVisible();
    await expect(page.locator('footer a[href="/privacy"]')).toBeVisible();
  });
});

test.describe('Analytics Disabled', () => {
  test('should not make any requests to PostHog', async ({ page }) => {
    const posthogRequests: string[] = [];

    page.on('request', (request) => {
      const url = request.url();
      if (url.includes('posthog') || url.includes('i.posthog.com')) {
        posthogRequests.push(url);
      }
    });

    await page.goto('/');
    await page.goto('/imprint');
    await page.goto('/privacy');
    await page.goto('/');

    expect(posthogRequests).toHaveLength(0);
  });
});
