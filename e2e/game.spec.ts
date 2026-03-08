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
  const pickGameHint = (page) => page.locator('#create-mode-placeholder');

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

    await expect(pickGameHint(page)).toBeVisible();
    await expect(marblesForm(page).locator('input[name="playerName"]')).not.toBeVisible();
    await expect(chessForm(page).locator('input[name="playerName"]')).not.toBeVisible();
  });

  test('should show both games and reveal form after selection', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByRole('heading', { name: 'Choose a Game' })).toBeVisible();
    await expect(modeCard(page).locator('.mode-tile[data-mode="marbles"] .mode-title')).toBeVisible();
    await expect(modeCard(page).locator('.mode-tile[data-mode="chess"] .mode-title')).toBeVisible();
    await expect(pickGameHint(page)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create Marbles' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Create Chess' })).not.toBeVisible();

    await modeCard(page).locator('.mode-tile[data-mode="marbles"]').click();
    await expect(marblesForm(page).getByRole('button', { name: 'Create Marbles' })).toBeVisible();

    await modeCard(page).locator('.mode-tile[data-mode="chess"]').click();
    await expect(page.getByRole('button', { name: 'Create Chess' })).toBeVisible();
  });

  test('player name input should be required', async ({ page }) => {
    await page.goto('/');
    await modeCard(page).locator('.mode-tile[data-mode="marbles"]').click();

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
    await modeCard(page).locator('.mode-tile[data-mode="marbles"]').click();
    
    // Fill in player name and submit
    await marblesForm(page).locator('input[name="playerName"]').fill('TestPlayer');
    await marblesForm(page).getByRole('button', { name: 'Create Marbles' }).click();
    
    // Should redirect to game page (game ID is 8 hex chars like d0ca5bf1)
    await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
    
    // Should show the game interface
    await expect(page.locator('h1')).toContainText('Marble Game');
  });

  test('should preserve player name in game', async ({ page }) => {
    await page.goto('/');
    await modeCard(page).locator('.mode-tile[data-mode="marbles"]').click();
    
    const playerName = 'E2ETestPlayer';
    await marblesForm(page).locator('input[name="playerName"]').fill(playerName);
    await marblesForm(page).getByRole('button', { name: 'Create Marbles' }).click();
    
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

  test('clock minutes input only shows when timed toggle is active', async ({ page }) => {
    await page.goto('/');

    await modeCard(page).locator('.mode-tile[data-mode="chess"]').click();
    const timedToggle = chessForm(page).locator('label.option-checkbox').first();
    const minutesWrap = chessForm(page).locator('#timed-config-wrap');

    await expect(minutesWrap).toHaveClass(/hidden/);

    await timedToggle.click();
    await expect(minutesWrap).not.toHaveClass(/hidden/);

    await timedToggle.click();
    await expect(minutesWrap).toHaveClass(/hidden/);
  });

  test('chess timed toggle and round minutes persist via local storage', async ({ page }) => {
    await page.goto('/');

    await modeCard(page).locator('.mode-tile[data-mode="chess"]').click();
    const timedToggle = chessForm(page).locator('label.option-checkbox').first();
    const minutesWrap = chessForm(page).locator('#timed-config-wrap');
    const minutesInput = chessForm(page).locator('#clock-minutes');

    await timedToggle.click();
    await expect(minutesWrap).not.toHaveClass(/hidden/);
    await minutesInput.fill('9');
    await minutesInput.blur();

    await page.reload();
    await modeCard(page).locator('.mode-tile[data-mode="chess"]').click();

    await expect(minutesWrap).not.toHaveClass(/hidden/);
    await expect(chessForm(page).locator('#timed-mode')).toBeChecked();
    await expect(minutesInput).toHaveValue('9');
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

  async function legalMoves(page: Page, gameId: string, from: string): Promise<string[]> {
    return page.evaluate(async ({ gameId, from }) => {
      const res = await fetch(`/chess/${gameId}/legal-moves?from=${encodeURIComponent(from)}`);
      if (!res.ok) return [];
      const text = await res.text();
      if (!text) return [];
      return text.split(',').filter(Boolean);
    }, { gameId, from });
  }

  async function resolveWhiteBlackPages(p1: Page, p2: Page, gameId: string) {
    const p1Moves = await legalMoves(p1, gameId, 'e2');
    if (p1Moves.length > 0) return { whitePage: p1, blackPage: p2 };
    const p2Moves = await legalMoves(p2, gameId, 'e2');
    if (p2Moves.length > 0) return { whitePage: p2, blackPage: p1 };
    throw new Error('Could not resolve white/black player pages via legal-moves');
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

      const { whitePage, blackPage } = await resolveWhiteBlackPages(p1, p2, gameId);

      const moveStatus = await postMove(whitePage, gameId, 'e2', 'e4');
      expect(moveStatus).toBe(200);

      // Turn should switch after a legal move.
      const secondWhiteMove = await postMove(whitePage, gameId, 'e4', 'e5');
      expect(secondWhiteMove).toBe(409);

      const blackReply = await postMove(blackPage, gameId, 'e7', 'e5');
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

      const { blackPage } = await resolveWhiteBlackPages(p1, p2, gameId);

      const wrongTurnStatus = await postMove(blackPage, gameId, 'e7', 'e5');
      expect(wrongTurnStatus).toBe(409);

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

      const { whitePage, blackPage } = await resolveWhiteBlackPages(p1, p2, gameId);

      // En passant sequence
      expect(await postMove(whitePage, gameId, 'e2', 'e4')).toBe(200);
      expect(await postMove(blackPage, gameId, 'a7', 'a6')).toBe(200);
      expect(await postMove(whitePage, gameId, 'e4', 'e5')).toBe(200);
      expect(await postMove(blackPage, gameId, 'd7', 'd5')).toBe(200);
      expect(await postMove(whitePage, gameId, 'e5', 'd6')).toBe(200);

      // Prepare and perform white kingside castling
      expect(await postMove(blackPage, gameId, 'a6', 'a5')).toBe(200);
      expect(await postMove(whitePage, gameId, 'g1', 'f3')).toBe(200);
      expect(await postMove(blackPage, gameId, 'a5', 'a4')).toBe(200);
      expect(await postMove(whitePage, gameId, 'f1', 'e2')).toBe(200);
      expect(await postMove(blackPage, gameId, 'h7', 'h6')).toBe(200);
      expect(await postMove(whitePage, gameId, 'e1', 'g1')).toBe(200);

      // Castled king cannot castle again from g1.
      expect(await postMove(blackPage, gameId, 'b7', 'b6')).toBe(200);
      expect(await postMove(whitePage, gameId, 'g1', 'e1')).toBe(400);
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

      const { whitePage, blackPage } = await resolveWhiteBlackPages(p1, p2, gameId);
      const blackWinnerName = blackPage === p1 ? 'Host' : 'Opponent';

      expect(await postMove(whitePage, gameId, 'f2', 'f3')).toBe(200);
      expect(await postMove(blackPage, gameId, 'e7', 'e5')).toBe(200);
      expect(await postMove(whitePage, gameId, 'g2', 'g4')).toBe(200);
      expect(await postMove(blackPage, gameId, 'd8', 'h4')).toBe(200);

      await expect.poll(async () => (await whitePage.locator('.hint').first().textContent())?.toLowerCase()).toContain('checkmate');
      await expect(blackPage.locator('.winner-text')).toContainText(blackWinnerName);
      await expect(whitePage.locator('.winner-text')).toContainText(blackWinnerName);
      await expect(whitePage.locator('.auto-restart-line')).toBeVisible();

      await expect.poll(async () => {
        return whitePage.locator('.auto-restart-line').count();
      }, { timeout: 20000 }).toBe(0);

      await expect(whitePage.locator('.winner-text')).toHaveCount(0);
      await expect(blackPage.locator('.winner-text')).toHaveCount(0);
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('new game after rotation keeps board orientation aligned with assigned color', async ({ browser }) => {
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

      const { whitePage, blackPage } = await resolveWhiteBlackPages(p1, p2, gameId);

      expect(await postMove(whitePage, gameId, 'f2', 'f3')).toBe(200);
      expect(await postMove(blackPage, gameId, 'e7', 'e5')).toBe(200);
      expect(await postMove(whitePage, gameId, 'g2', 'g4')).toBe(200);
      expect(await postMove(blackPage, gameId, 'd8', 'h4')).toBe(200);

      await expect(p1.locator('.winner-text')).toBeVisible();

      await expect.poll(async () => {
        return p1.locator('.auto-restart-line').count();
      }, { timeout: 25000 }).toBe(0);

      await expect.poll(async () => {
        const pages = [p1, p2, p3];
        let activeCount = 0;
        let activeOrientationOk = 0;
        let spectatorPromoted = false;

        for (const page of pages) {
          const board = page.locator('.chess-board');
          if (await board.count() === 0) {
            continue;
          }

          const yourColor = (await board.first().getAttribute('data-your-color')) || '';
          const perspective = (await board.first().getAttribute('data-perspective')) || '';
          const firstSquare = (await board.first().locator('.chess-square').first().getAttribute('data-square')) || '';

          if (page === p3 && (yourColor === 'white' || yourColor === 'black')) {
            spectatorPromoted = true;
          }

          if (yourColor === 'white') {
            activeCount += 1;
            if (perspective === 'white' && firstSquare === 'a8') activeOrientationOk += 1;
          } else if (yourColor === 'black') {
            activeCount += 1;
            if (perspective === 'black' && firstSquare === 'h1') activeOrientationOk += 1;
          }
        }

        return activeCount === 2 && activeOrientationOk === 2 && spectatorPromoted;
      }, { timeout: 10000 }).toBe(true);
    } finally {
      await p1Context.close();
      await p2Context.close();
      await p3Context.close();
    }
  });

  test('active player can surrender and spectators do not see surrender button', async ({ browser }) => {
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

      await expect(p1.locator('.chess-surrender-btn')).toBeVisible();
      await expect(p2.locator('.chess-surrender-btn')).toBeVisible();
      await expect(p3.locator('.chess-surrender-btn')).toHaveCount(0);

      await p1.locator('.chess-surrender-btn').click();
      await expect(p1.locator('#surrender-modal')).toBeVisible();
      await p1.locator('#surrender-confirm-btn').click();

      await expect(p1.locator('.winner-text')).toContainText('Opponent');
      await expect(p2.locator('.winner-text')).toContainText('Opponent');
    } finally {
      await p1Context.close();
      await p2Context.close();
      await p3Context.close();
    }
  });

  test('last move indicator appears on both players after move', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'Host');
      await joinChessGame(p2, gameId, 'Opponent');

      const { whitePage } = await resolveWhiteBlackPages(p1, p2, gameId);

      const status = await postMove(whitePage, gameId, 'e2', 'e4');
      expect(status).toBe(200);

      await expect.poll(async () => await p1.locator('.chess-square[data-square="e2"]').evaluate((el) => el.classList.contains('last-move-from'))).toBe(true);
      await expect.poll(async () => await p1.locator('.chess-square[data-square="e4"]').evaluate((el) => el.classList.contains('last-move-to'))).toBe(true);
      await expect.poll(async () => await p2.locator('.chess-square[data-square="e2"]').evaluate((el) => el.classList.contains('last-move-from'))).toBe(true);
      await expect.poll(async () => await p2.locator('.chess-square[data-square="e4"]').evaluate((el) => el.classList.contains('last-move-to'))).toBe(true);
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('desktop right-click annotations support arrows, alt arrows, marks, and left-click clear', async ({ browser }) => {
    const p1Context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'Host');
      await joinChessGame(p2, gameId, 'Opponent');

      const d2 = p1.locator('.chess-square[data-square="d2"]');
      const d4 = p1.locator('.chess-square[data-square="d4"]');
      const c2 = p1.locator('.chess-square[data-square="c2"]');
      const c4 = p1.locator('.chess-square[data-square="c4"]');
      const e2 = p1.locator('.chess-square[data-square="e2"]');

      const center = async (loc: ReturnType<Page['locator']>) => {
        const box = await loc.boundingBox();
        if (!box) throw new Error('Missing square bounding box');
        return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
      };

      const d2p = await center(d2);
      const d4p = await center(d4);
      await p1.mouse.move(d2p.x, d2p.y);
      await p1.mouse.down({ button: 'right' });
      await p1.mouse.move(d4p.x, d4p.y);
      await expect(p1.locator('.chess-arrow-line.preview')).toHaveCount(1);
      await p1.mouse.up({ button: 'right' });
      await expect(p1.locator('.chess-arrow-line.preview')).toHaveCount(0);

      await expect(p1.locator('.chess-arrow-line')).toHaveCount(1);

      const c2p = await center(c2);
      const c4p = await center(c4);
      await p1.keyboard.down('Shift');
      await p1.mouse.move(c2p.x, c2p.y);
      await p1.mouse.down({ button: 'right' });
      await p1.mouse.move(c4p.x, c4p.y);
      await expect(p1.locator('.chess-arrow-line.preview.alt')).toHaveCount(1);
      await p1.mouse.up({ button: 'right' });
      await p1.keyboard.up('Shift');
      await expect(p1.locator('.chess-arrow-line.alt')).toHaveCount(1);

      await p1.keyboard.down('Shift');
      await d2.click({ button: 'right' });
      await p1.keyboard.up('Shift');
      await expect(d2).toHaveClass(/marked-alt/);

      await e2.click();
      await expect(p1.locator('.chess-arrow-line')).toHaveCount(0);
      await expect(p1.locator('.chess-square.marked, .chess-square.marked-alt')).toHaveCount(0);
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('resume reconnect helpers are present for client-side recovery hooks', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'Host');
      await joinChessGame(p2, gameId, 'Opponent');

      await p1.evaluate(() => {
        if (window.__chessDebug && typeof window.__chessDebug.setDisableScheduledReconnect === 'function') {
          window.__chessDebug.setDisableScheduledReconnect(true);
        }
      });

      const debugHooks = await p1.evaluate(() => {
        const dbg = window.__chessDebug || {};
        return {
          onResumeVisibilityChange: typeof dbg.onResumeVisibilityChange,
          onResumeFocus: typeof dbg.onResumeFocus,
          onResumePageShow: typeof dbg.onResumePageShow,
          simulateResumeRecovery: typeof dbg.simulateResumeRecovery,
          setDisableScheduledReconnect: typeof dbg.setDisableScheduledReconnect,
          wsState: typeof dbg.wsState,
        };
      });

      expect(debugHooks.onResumeVisibilityChange).toBe('function');
      expect(debugHooks.onResumeFocus).toBe('function');
      expect(debugHooks.onResumePageShow).toBe('function');
      expect(debugHooks.simulateResumeRecovery).toBe('function');
      expect(debugHooks.setDisableScheduledReconnect).toBe('function');
      expect(debugHooks.wsState).toBe('function');

      const beforeSimulated = await p1.evaluate(() => {
        if (window.__chessDebug && typeof window.__chessDebug.wsState === 'function') {
          return window.__chessDebug.wsState();
        }
        return null;
      });

      await p1.evaluate(() => {
        if (window.__chessDebug && typeof window.__chessDebug.simulateResumeRecovery === 'function') {
          window.__chessDebug.simulateResumeRecovery();
        }
      });

      await expect.poll(async () => {
        return await p1.evaluate(() => {
          if (window.__chessDebug && typeof window.__chessDebug.wsState === 'function') {
            const wsState = window.__chessDebug.wsState();
            return !!(wsState && wsState.resumeReconnectAttempts > 0);
          }
          return false;
        });
      }, { timeout: 4000 }).toBe(true);

      const afterSimulated = await p1.evaluate(() => {
        if (window.__chessDebug && typeof window.__chessDebug.wsState === 'function') {
          return window.__chessDebug.wsState();
        }
        return null;
      });

      expect(beforeSimulated).not.toBeNull();
      expect(afterSimulated).not.toBeNull();
      expect((afterSimulated as any).resumeReconnectAttempts).toBeGreaterThan((beforeSimulated as any).resumeReconnectAttempts);
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
