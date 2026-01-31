import { test, expect } from '@playwright/test';

/**
 * E2E tests for the MarbleGame application.
 *
 * These tests run with POSTHOG_ENABLED=false (configured in playwright.config.ts)
 * to prevent analytics from being triggered during testing.
 */

test.describe('Homepage', () => {
  test('should load the homepage', async ({ page }) => {
    await page.goto('/');
    
    // Check page title
    await expect(page).toHaveTitle(/Marble Game/);
    
    // Check main heading
    await expect(page.locator('h1')).toContainText('Marble Game');
  });

  test('should not include PostHog script when analytics disabled', async ({ page }) => {
    await page.goto('/');
    
    // PostHog should not be present when POSTHOG_ENABLED=false
    const posthogScript = await page.evaluate(() => {
      return typeof (window as any).posthog !== 'undefined';
    });
    expect(posthogScript).toBe(false);
  });

  test('should not show cookie banner when analytics disabled', async ({ page }) => {
    await page.goto('/');
    
    // Cookie banner should not exist when PostHog is disabled
    const cookieBanner = page.locator('#cookie-banner');
    await expect(cookieBanner).toHaveCount(0);
  });

  test('should have create game form', async ({ page }) => {
    await page.goto('/');
    
    // Check form elements
    await expect(page.locator('input[name="playerName"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
  });

  test('player name input should be required', async ({ page }) => {
    await page.goto('/');
    
    const input = page.locator('input[name="playerName"]');
    
    // The input should have the required attribute
    await expect(input).toHaveAttribute('required');
  });
});

test.describe('Game Creation', () => {
  test('should create a new game', async ({ page }) => {
    await page.goto('/');
    
    // Fill in player name and submit
    await page.locator('input[name="playerName"]').fill('TestPlayer');
    await page.locator('button[type="submit"]').click();
    
    // Should redirect to game page (game ID is 8 hex chars like d0ca5bf1)
    await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
    
    // Should show the game interface
    await expect(page.locator('h1')).toContainText('Marble Game');
  });

  test('should preserve player name in game', async ({ page }) => {
    await page.goto('/');
    
    const playerName = 'E2ETestPlayer';
    await page.locator('input[name="playerName"]').fill(playerName);
    await page.locator('button[type="submit"]').click();
    
    // Wait for game page to load (game ID is 8 hex chars)
    await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
    
    // Player name should be visible somewhere on the page
    await expect(page.getByText(playerName)).toBeVisible();
  });
});

test.describe('Game Joining', () => {
  test('should show 404 page for non-existent game', async ({ page }) => {
    // Try to access a game that doesn't exist
    const response = await page.goto('/game/00000000');
    
    // Should return 404 status
    expect(response?.status()).toBe(404);
    
    // Should show error message and back button
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
    
    // Check footer links exist
    await expect(page.locator('footer a[href="/imprint"]')).toBeVisible();
    await expect(page.locator('footer a[href="/privacy"]')).toBeVisible();
  });
});

test.describe('Analytics Disabled', () => {
  test('should not make any requests to PostHog', async ({ page }) => {
    const posthogRequests: string[] = [];
    
    // Monitor network requests
    page.on('request', (request) => {
      const url = request.url();
      if (url.includes('posthog') || url.includes('i.posthog.com')) {
        posthogRequests.push(url);
      }
    });
    
    // Navigate through multiple pages
    await page.goto('/');
    await page.goto('/imprint');
    await page.goto('/privacy');
    await page.goto('/');
    
    // No PostHog requests should have been made
    expect(posthogRequests).toHaveLength(0);
  });
});
