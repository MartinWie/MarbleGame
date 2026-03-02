import { test, expect, Page } from '@playwright/test';

/**
 * E2E tests for the MarbleGame application.
 *
 * These tests run with POSTHOG_ENABLED=false (configured in playwright.config.ts)
 * to prevent analytics from being triggered during testing.
 */

test.describe('Homepage', () => {
  const modeCard = (page) => page.locator('.mode-selector-card');
  const marblesForm = (page) => page.locator('#create-form-marbles');
  const chessForm = (page) => page.locator('#create-form-chess');

  test('should load the homepage', async ({ page }) => {
    await page.goto('/');
    
    // Check page title
    await expect(page).toHaveTitle(/Games/);
    
    // Check main heading
    await expect(page.locator('h1')).toContainText('Games');
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
    await expect(marblesForm(page).locator('input[name="playerName"]')).toBeVisible();
    await expect(marblesForm(page).getByRole('button', { name: 'Create Game' })).toBeVisible();
  });

  test('should show both marbles and chess create actions', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByRole('heading', { name: 'Choose Your Game' })).toBeVisible();
    await expect(modeCard(page).locator('.mode-tile[data-mode="marbles"] h3')).toBeVisible();
    await expect(modeCard(page).locator('.mode-tile[data-mode="chess"] h3')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create Game' })).toBeVisible();

    await modeCard(page).locator('.mode-tile[data-mode="chess"]').click();
    await expect(page.getByRole('button', { name: 'Create Chess' })).toBeVisible();
  });

  test('player name input should be required', async ({ page }) => {
    await page.goto('/');

    const input = marblesForm(page).locator('input[name="playerName"]');
    
    // The input should have the required attribute
    await expect(input).toHaveAttribute('required');
  });
});

test.describe('Game Creation', () => {
  const modeCard = (page) => page.locator('.mode-selector-card');
  const marblesForm = (page) => page.locator('#create-form-marbles');
  const chessForm = (page) => page.locator('#create-form-chess');

  test('should create a new game', async ({ page }) => {
    await page.goto('/');
    
    // Fill in player name and submit
    await marblesForm(page).locator('input[name="playerName"]').fill('TestPlayer');
    await marblesForm(page).getByRole('button', { name: 'Create Game' }).click();
    
    // Should redirect to game page (game ID is 8 hex chars like d0ca5bf1)
    await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
    
    // Should show the game interface
    await expect(page.locator('h1')).toContainText('Games');
  });

  test('should preserve player name in game', async ({ page }) => {
    await page.goto('/');
    
    const playerName = 'E2ETestPlayer';
    await marblesForm(page).locator('input[name="playerName"]').fill(playerName);
    await marblesForm(page).getByRole('button', { name: 'Create Game' }).click();
    
    // Wait for game page to load (game ID is 8 hex chars)
    await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
    
    // Player name should be visible somewhere on the page
    await expect(page.getByText(playerName)).toBeVisible();
  });

  test('should create a new chess game', async ({ page }) => {
    await page.goto('/');

    await modeCard(page).locator('.mode-tile[data-mode="chess"]').click();
    await chessForm(page).locator('input[name="playerName"]').fill('ChessHost');
    await chessForm(page).getByRole('button', { name: 'Create Chess' }).click();

    await expect(page).toHaveURL(/\/chess\/[a-f0-9]{8}/);
    await expect(page.locator('h1')).toContainText('Chess Game');
  });
});

test.describe('Chess Joining', () => {
  const modeCard = (page: Page) => page.locator('.mode-selector-card');
  const chessForm = (page: Page) => page.locator('#create-form-chess');

  async function createChessGame(page: Page, name: string) {
    await page.goto('/');
    await modeCard(page).locator('.mode-tile[data-mode="chess"]').click();
    await chessForm(page).locator('input[name="playerName"]').fill(name);
    await chessForm(page).getByRole('button', { name: 'Create Chess' }).click();
    await expect(page).toHaveURL(/\/chess\/[a-f0-9]{8}/);
    const match = new URL(page.url()).pathname.match(/^\/chess\/([a-f0-9]{8})$/);
    expect(match).not.toBeNull();
    return match![1];
  }

  async function joinChessGame(page: Page, gameId: string, name: string) {
    await page.goto(`/chess/${gameId}/join`);
    await page.locator('input[name="playerName"]').fill(name);
    await page.getByRole('button', { name: 'Join Game' }).click();
    await expect(page).toHaveURL(new RegExp(`/chess/${gameId}$`));
  }

  async function postMove(page: Page, gameId: string, from: string, to: string) {
    return page.evaluate(async ({ gameId, from, to }) => {
      const body = new URLSearchParams({ from, to });
      const response = await fetch(`/chess/${gameId}/move`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: body.toString(),
      });
      return response.status;
    }, { gameId, from, to });
  }

  test('second player can join as opponent, third as spectator', async ({ browser }) => {
    const hostContext = await browser.newContext();
    const host = await hostContext.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();
    const p3Context = await browser.newContext();
    const p3 = await p3Context.newPage();

    try {
      await host.goto('/');
      await modeCard(host).locator('.mode-tile[data-mode="chess"]').click();
      await chessForm(host).locator('input[name="playerName"]').fill('Host');
      await chessForm(host).getByRole('button', { name: 'Create Chess' }).click();
      await expect(host).toHaveURL(/\/chess\/[a-f0-9]{8}/);

      const gameId = host.url().split('/chess/')[1];

      await p2.goto(`/chess/${gameId}/join`);
      await p2.locator('input[name="playerName"]').fill('Opponent');
      await p2.getByRole('button', { name: 'Join Game' }).click();
      await expect(p2).toHaveURL(new RegExp(`/chess/${gameId}$`));

      await p3.goto(`/chess/${gameId}/join`);
      await p3.locator('input[name="playerName"]').fill('Spectator');
      await p3.getByRole('button', { name: 'Join Game' }).click();
      await expect(p3).toHaveURL(new RegExp(`/chess/${gameId}$`));

      await expect(host.locator('.player-name:has-text("Opponent")')).toBeVisible({ timeout: 5000 });
      await expect(host.locator('.player-name:has-text("Spectator")')).toBeVisible({ timeout: 5000 });
      await expect(p3.locator('.your-status')).toContainText('spectating');
    } finally {
      await hostContext.close();
      await p2Context.close();
      await p3Context.close();
    }
  });

  test('two players can play moves and board updates for both', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'Host');
      await joinChessGame(p2, gameId, 'Opponent');

      const moveStatus = await postMove(p1, gameId, 'e2', 'e4');
      expect(moveStatus).toBe(200);

      // Turn should switch after a legal move.
      const secondWhiteMove = await postMove(p1, gameId, 'e4', 'e5');
      expect(secondWhiteMove).toBe(400);

      const blackReply = await postMove(p2, gameId, 'e7', 'e5');
      expect(blackReply).toBe(200);
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('spectator and wrong-turn moves are rejected', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();
    const p3Context = await browser.newContext();
    const p3 = await p3Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'Host');
      await joinChessGame(p2, gameId, 'Opponent');
      await joinChessGame(p3, gameId, 'Spectator');

      const wrongTurnStatus = await postMove(p2, gameId, 'e7', 'e5');
      expect(wrongTurnStatus).toBe(400);

      const spectatorStatus = await postMove(p3, gameId, 'e2', 'e4');
      expect(spectatorStatus).toBe(400);
    } finally {
      await p1Context.close();
      await p2Context.close();
      await p3Context.close();
    }
  });

  test('en passant and castling are supported', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'Host');
      await joinChessGame(p2, gameId, 'Opponent');

      // En passant sequence
      expect(await postMove(p1, gameId, 'e2', 'e4')).toBe(200);
      expect(await postMove(p2, gameId, 'a7', 'a6')).toBe(200);
      expect(await postMove(p1, gameId, 'e4', 'e5')).toBe(200);
      expect(await postMove(p2, gameId, 'd7', 'd5')).toBe(200);
      expect(await postMove(p1, gameId, 'e5', 'd6')).toBe(200);

      // Prepare and perform white kingside castling
      expect(await postMove(p2, gameId, 'a6', 'a5')).toBe(200);
      expect(await postMove(p1, gameId, 'g1', 'f3')).toBe(200);
      expect(await postMove(p2, gameId, 'a5', 'a4')).toBe(200);
      expect(await postMove(p1, gameId, 'f1', 'e2')).toBe(200);
      expect(await postMove(p2, gameId, 'h7', 'h6')).toBe(200);
      expect(await postMove(p1, gameId, 'e1', 'g1')).toBe(200);

      // Castled king cannot castle again from g1.
      expect(await postMove(p2, gameId, 'b7', 'b6')).toBe(200);
      expect(await postMove(p1, gameId, 'g1', 'e1')).toBe(400);
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('fools mate shows checkmate and winner UI', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'Host');
      await joinChessGame(p2, gameId, 'Opponent');

      expect(await postMove(p1, gameId, 'f2', 'f3')).toBe(200);
      expect(await postMove(p2, gameId, 'e7', 'e5')).toBe(200);
      expect(await postMove(p1, gameId, 'g2', 'g4')).toBe(200);
      expect(await postMove(p2, gameId, 'd8', 'h4')).toBe(200);

      await expect.poll(async () => (await p1.locator('.hint').first().textContent())?.toLowerCase()).toContain('checkmate');
      await expect(p1.locator('.winner-text')).toContainText('Opponent');
      await expect(p2.locator('.winner-text')).toContainText('Opponent');
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
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
