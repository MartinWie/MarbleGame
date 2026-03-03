import { test, expect, devices } from '@playwright/test';

async function createChessGame(page, name: string) {
  await page.goto('/');
  await page.locator('.mode-tile[data-mode="chess"]').click();
  await page.locator('#create-form-chess input[name="playerName"]').fill(name);
  await page.getByRole('button', { name: 'Create Chess' }).click();
  await expect(page).toHaveURL(/\/chess\/[a-f0-9]{8}/);
  return new URL(page.url()).pathname.split('/chess/')[1];
}

async function joinChessGame(page, gameId: string, name: string) {
  await page.goto(`/chess/${gameId}/join`);
  await page.locator('input[name="playerName"]').fill(name);
  await page.getByRole('button', { name: 'Join Game' }).click();
  await expect(page).toHaveURL(new RegExp(`/chess/${gameId}$`));
}

test.describe('Chess Fixes', () => {
  test('move trail keeps moved piece color class', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'HostTrail');
      await joinChessGame(p2, gameId, 'GuestTrail');

      await expect(p1.locator('.player-name:has-text("GuestTrail")')).toBeVisible();

      await p1.locator('.chess-square[data-square="e2"]').click();
      await p1.locator('.chess-square[data-square="e4"]').click();

      await expect(p1.locator('.move-trail-piece.piece-white')).toBeVisible();

      await p2.locator('.chess-square[data-square="e7"]').click();
      await p2.locator('.chess-square[data-square="e5"]').click();

      await expect(p2.locator('.move-trail-piece.piece-black')).toBeVisible();
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('mobile share rejection does not fallback to clipboard', async ({ browser }) => {
    const context = await browser.newContext({ ...devices['Pixel 7'] });
    const page = await context.newPage();

    try {
      await page.addInitScript(() => {
        let shareCalls = 0;
        let clipboardCalls = 0;
        const shareMock = () => {
          shareCalls += 1;
          return Promise.reject(new Error('share-rejected'));
        };
        const clipboardMock = {
          writeText: () => {
            clipboardCalls += 1;
            return Promise.resolve();
          },
        };

        Object.defineProperty(window.navigator, 'share', {
          configurable: true,
          value: shareMock,
        });
        Object.defineProperty(window.navigator, 'clipboard', {
          configurable: true,
          value: clipboardMock,
        });
        Object.defineProperty(window.navigator, '__shareTest', {
          configurable: true,
          value: {
            getShareCalls: () => shareCalls,
            getClipboardCalls: () => clipboardCalls,
          },
        });
      });

      await createChessGame(page, 'HostShare');

      const shareBtn = page.locator('#share-btn');
      await expect(shareBtn).toHaveAttribute('data-share-url', /\/chess\/[a-f0-9]{8}\/join/);
      await expect(shareBtn).not.toHaveAttribute('onclick', /.+/);

      await shareBtn.click();

      await expect
        .poll(async () => {
          return page.evaluate(() => {
            return (window.navigator as unknown as { __shareTest?: { getShareCalls: () => number } }).__shareTest?.getShareCalls() ?? -1;
          });
        })
        .toBe(1);

      await expect
        .poll(async () => {
          return page.evaluate(() => {
            return (window.navigator as unknown as { __shareTest?: { getClipboardCalls: () => number } }).__shareTest?.getClipboardCalls() ?? -1;
          });
        })
        .toBe(0);
    } finally {
      await context.close();
    }
  });
});
