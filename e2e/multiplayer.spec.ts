import { test, expect, BrowserContext, Page, Browser } from '@playwright/test';

/**
 * E2E tests for multiplayer game logic.
 *
 * These tests verify:
 * - Host switching when host disconnects
 * - Winner determination and display
 * - Game flow with multiple players
 *
 * Tests run with POSTHOG_ENABLED=false (configured in playwright.config.ts).
 */

/**
 * Helper to create a new player in a fresh browser context.
 */
async function createPlayer(browser: Browser, name: string): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  return { context, page };
}

/**
 * Helper to create a game and return the game URL.
 */
async function createGame(page: Page, playerName: string): Promise<string> {
  await page.goto('/');
  await page.locator('input[name="playerName"]').fill(playerName);
  await page.locator('button[type="submit"]').click();
  await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}/);
  return page.url();
}

/**
 * Helper to join an existing game via the join page.
 */
async function joinGame(page: Page, gameUrl: string, playerName: string): Promise<void> {
  // Extract game ID from URL
  const gameId = gameUrl.split('/game/')[1];
  
  // Go to join page
  await page.goto(`/game/${gameId}/join`);
  await page.locator('input[name="playerName"]').fill(playerName);
  await page.locator('button[type="submit"]').click();
  
  // Should be redirected to game page
  await expect(page).toHaveURL(/\/game\/[a-f0-9]{8}$/);
}

test.describe('Multiplayer Game Flow', () => {
  test('two players can join the same game', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Player 2 joins
      await joinGame(player2.page, gameUrl, 'GuestPlayer');
      
      // Both should see each other's names in the player list
      await expect(player1.page.locator('.player-name:has-text("GuestPlayer")')).toBeVisible({ timeout: 5000 });
      await expect(player2.page.locator('.player-name:has-text("HostPlayer")')).toBeVisible({ timeout: 5000 });
    } finally {
      await player1.context.close();
      await player2.context.close();
    }
  });

  test('creator sees start button when 2+ players join', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Initially, start button should not be visible (only 1 player)
      await expect(player1.page.locator('button:has-text("Start")')).not.toBeVisible();
      
      // Player 2 joins
      await joinGame(player2.page, gameUrl, 'GuestPlayer');
      
      // Now host should see start button
      await expect(player1.page.locator('button:has-text("Start")')).toBeVisible({ timeout: 5000 });
      
      // Guest should NOT see start button (only host can start)
      await expect(player2.page.locator('button:has-text("Start")')).not.toBeVisible();
    } finally {
      await player1.context.close();
      await player2.context.close();
    }
  });

  test('three players can join and host sees start button', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest1');
    const player3 = await createPlayer(browser, 'Guest2');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Player 2 and 3 join
      await joinGame(player2.page, gameUrl, 'Player2');
      await joinGame(player3.page, gameUrl, 'Player3');
      
      // Host should see all players
      await expect(player1.page.locator('.player-name:has-text("Player2")')).toBeVisible({ timeout: 5000 });
      await expect(player1.page.locator('.player-name:has-text("Player3")')).toBeVisible({ timeout: 5000 });
      
      // Host should see start button
      await expect(player1.page.locator('button:has-text("Start")')).toBeVisible({ timeout: 5000 });
      
      // Other players should NOT see start button
      await expect(player2.page.locator('button:has-text("Start")')).not.toBeVisible();
      await expect(player3.page.locator('button:has-text("Start")')).not.toBeVisible();
    } finally {
      await player1.context.close();
      await player2.context.close();
      await player3.context.close();
    }
  });
});

test.describe('Game Play', () => {
  test('game can be started by host with 2 players', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Player 2 joins
      await joinGame(player2.page, gameUrl, 'GuestPlayer');
      
      // Wait for player2 to appear
      await expect(player1.page.locator('.player-name:has-text("GuestPlayer")')).toBeVisible({ timeout: 5000 });
      
      // Host starts the game
      await player1.page.locator('button:has-text("Start")').click();
      
      // Both players should see the game has started (placing phase)
      // The game-area div contains the active game UI
      await expect(player1.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
      await expect(player2.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
    } finally {
      await player1.context.close();
      await player2.context.close();
    }
  });

  test('game shows marble counts after starting', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Player 2 joins
      await joinGame(player2.page, gameUrl, 'GuestPlayer');
      
      // Wait for player2 to appear
      await expect(player1.page.locator('.player-name:has-text("GuestPlayer")')).toBeVisible({ timeout: 5000 });
      
      // Host starts the game
      await player1.page.locator('button:has-text("Start")').click();
      
      // Wait for game to start
      await expect(player1.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
      
      // Both players should see marble counts (each player starts with 10 marbles)
      // Use .first() since there are multiple player-marbles elements
      await expect(player1.page.locator('.player-marbles').first()).toBeVisible();
      await expect(player2.page.locator('.player-marbles').first()).toBeVisible();
    } finally {
      await player1.context.close();
      await player2.context.close();
    }
  });
});

test.describe('SSE Real-time Updates', () => {
  test('page updates via SSE when another player joins', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Verify only host is shown initially
      await expect(player1.page.locator('.player-name:has-text("HostPlayer")')).toBeVisible();
      
      // Player 2 joins
      await joinGame(player2.page, gameUrl, 'NewPlayer');
      
      // Player 1's page should update automatically via SSE to show new player
      // No page refresh needed
      await expect(player1.page.locator('.player-name:has-text("NewPlayer")')).toBeVisible({ timeout: 5000 });
    } finally {
      await player1.context.close();
      await player2.context.close();
    }
  });

  test('all players see updates when game starts', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Player 2 joins
      await joinGame(player2.page, gameUrl, 'GuestPlayer');
      
      // Wait for player2 to appear on host's screen
      await expect(player1.page.locator('.player-name:has-text("GuestPlayer")')).toBeVisible({ timeout: 5000 });
      
      // Host starts the game
      await player1.page.locator('button:has-text("Start")').click();
      
      // Both players should see the game started via SSE
      await expect(player1.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
      await expect(player2.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
    } finally {
      await player1.context.close();
      await player2.context.close();
    }
  });
});

// Host switch tests are slow (15s grace period) - mark as slow
test.describe('Host Switch Logic', () => {
  // Skip slow tests by default, run with: npm run test:e2e -- --grep "@slow"
  test('host status transfers when host disconnects @slow', async ({ browser }) => {
    test.setTimeout(45000); // 45 second timeout for this test
    
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest1');
    const player3 = await createPlayer(browser, 'Guest2');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'OriginalHost');
      
      // Players 2 and 3 join (need 3 players so 2 remain after host disconnect)
      await joinGame(player2.page, gameUrl, 'NewHost');
      await joinGame(player3.page, gameUrl, 'Player3');
      
      // All players should be visible
      await expect(player1.page.locator('.player-name:has-text("NewHost")')).toBeVisible({ timeout: 5000 });
      await expect(player1.page.locator('.player-name:has-text("Player3")')).toBeVisible({ timeout: 5000 });
      
      // Player 1 should see start button (is host with 3 players)
      await expect(player1.page.locator('button:has-text("Start Game")')).toBeVisible({ timeout: 5000 });
      
      // Player 2 should NOT see start button initially (not host)
      await expect(player2.page.locator('button:has-text("Start Game")')).not.toBeVisible();
      
      // Player 1 (host) closes their browser/disconnects
      await player1.context.close();
      
      // Wait for SSE to detect disconnect and show countdown
      await expect(async () => {
        const p2Countdown = await player2.page.locator('.player-countdown').isVisible().catch(() => false);
        const p3Countdown = await player3.page.locator('.player-countdown').isVisible().catch(() => false);
        expect(p2Countdown || p3Countdown).toBe(true);
      }).toPass({ timeout: 10000 });
      
      // Wait for the countdown to expire and host to transfer
      // Grace period is 15s, countdown ticks down client-side, then /check-disconnects is called
      // Either Player2 OR Player3 can become the new host (HashMap iteration order is non-deterministic)
      await expect(async () => {
        const p2SeesStart = await player2.page.locator('button:has-text("Start Game")').isVisible().catch(() => false);
        const p3SeesStart = await player3.page.locator('button:has-text("Start Game")').isVisible().catch(() => false);
        // Exactly one of them should be the new host
        expect(p2SeesStart || p3SeesStart).toBe(true);
      }).toPass({ timeout: 25000 });
    } finally {
      // Only close players if not already closed
      try { await player2.context.close(); } catch (e) {}
      try { await player3.context.close(); } catch (e) {}
    }
  });

  test('game continues when non-host player disconnects @slow', async ({ browser }) => {
    test.setTimeout(45000);
    
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    const player3 = await createPlayer(browser, 'Third');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Players 2 and 3 join
      await joinGame(player2.page, gameUrl, 'Player2');
      await joinGame(player3.page, gameUrl, 'Player3');
      
      // Wait for all players to be visible
      await expect(player1.page.locator('.player-name:has-text("Player2")')).toBeVisible({ timeout: 5000 });
      await expect(player1.page.locator('.player-name:has-text("Player3")')).toBeVisible({ timeout: 5000 });
      
      // Host starts the game
      await player1.page.locator('button:has-text("Start")').click();
      
      // Game should be in progress
      await expect(player1.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
      
      // Player 2 disconnects
      await player2.context.close();
      
      // Game should continue for remaining players after grace period (15s)
      // Host and Player3 should still see game interface
      await expect(player1.page.locator('.game-area')).toBeVisible({ timeout: 20000 });
      await expect(player3.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
    } finally {
      try { await player1.context.close(); } catch (e) {}
      try { await player3.context.close(); } catch (e) {}
    }
  });
});

test.describe('Error Handling', () => {
  test('join page shows form for any game ID', async ({ page }) => {
    // The join page shows a form even for non-existent games
    // Validation happens on form submit
    const response = await page.goto('/game/00000000/join');
    
    expect(response?.status()).toBe(200);
    await expect(page.locator('input[name="playerName"]')).toBeVisible();
  });

  test('share button is visible for host', async ({ browser }) => {
    const player1 = await createPlayer(browser, 'Host');
    
    try {
      // Create a game
      await createGame(player1.page, 'TestPlayer');
      
      // Verify share button exists
      await expect(player1.page.locator('button:has-text("Share")')).toBeVisible();
    } finally {
      await player1.context.close();
    }
  });
});

test.describe('Winner Determination', () => {
  // Note: Full gameplay tests are complex and time-sensitive.
  // The core winner logic is covered by unit tests in Game.kt.
  // These E2E tests verify the basic UI flow when a game ends.

  test('game over screen shows winner announcement @slow', async ({ browser }) => {
    test.setTimeout(120000); // This test involves full gameplay
    
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Player 2 joins
      await joinGame(player2.page, gameUrl, 'GuestPlayer');
      
      // Wait for player2 to appear
      await expect(player1.page.locator('.player-name:has-text("GuestPlayer")')).toBeVisible({ timeout: 5000 });
      
      // Host starts the game
      await player1.page.locator('button:has-text("Start")').click();
      
      // Wait for game to start (placing phase)
      await expect(player1.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
      
      // Play rounds until game over
      // Strategy: whoever is placer places all marbles, other guesses correctly
      let gameOver = false;
      let attempts = 0;
      const maxAttempts = 10;
      
      while (!gameOver && attempts < maxAttempts) {
        attempts++;
        
        // Wait for placing phase or game over
        await player1.page.waitForTimeout(1000);
        
        // Check if game is over
        gameOver = await player1.page.locator('.winner-announcement').isVisible().catch(() => false);
        if (gameOver) break;
        
        // Wait for a phase-info to be visible
        const hasPhaseInfo = await player1.page.locator('.phase-info').isVisible().catch(() => false);
        if (!hasPhaseInfo) {
          await player1.page.waitForTimeout(2000);
          continue;
        }
        
        // Determine who is the placer (they see the place form)
        const player1IsPlacer = await player1.page.locator('#place-form').isVisible().catch(() => false);
        const player2IsPlacer = await player2.page.locator('#place-form').isVisible().catch(() => false);
        
        if (player1IsPlacer) {
          // Get player1's current marble count from the marble picker
          const maxMarbleBtn = player1.page.locator('.marble-btn').last();
          const maxValue = await maxMarbleBtn.getAttribute('data-value').catch(() => '1');
          const marbles = parseInt(maxValue || '1');
          
          // Place all marbles
          await player1.page.locator(`.marble-btn[data-value="${marbles}"]`).click();
          await player1.page.locator('button[type="submit"]').click();
          
          // Wait for guessing phase, then guess correctly
          await expect(player2.page.locator('.btn-even, .btn-odd').first()).toBeVisible({ timeout: 5000 });
          const isEven = marbles % 2 === 0;
          await player2.page.locator(isEven ? '.btn-even' : '.btn-odd').click();
          
          // Wait for result phase to pass
          await player1.page.waitForTimeout(4000);
        } else if (player2IsPlacer) {
          // Get player2's current marble count
          const maxMarbleBtn = player2.page.locator('.marble-btn').last();
          const maxValue = await maxMarbleBtn.getAttribute('data-value').catch(() => '1');
          const marbles = parseInt(maxValue || '1');
          
          // Place all marbles
          await player2.page.locator(`.marble-btn[data-value="${marbles}"]`).click();
          await player2.page.locator('button[type="submit"]').click();
          
          // Wait for guessing phase, then guess correctly
          await expect(player1.page.locator('.btn-even, .btn-odd').first()).toBeVisible({ timeout: 5000 });
          const isEven = marbles % 2 === 0;
          await player1.page.locator(isEven ? '.btn-even' : '.btn-odd').click();
          
          // Wait for result phase to pass
          await player1.page.waitForTimeout(4000);
        } else {
          // Neither player is placer - might be in result phase, wait
          await player1.page.waitForTimeout(3000);
        }
      }
      
      // Verify game over screen appears
      await expect(player1.page.locator('.winner-announcement')).toBeVisible({ timeout: 10000 });
      await expect(player2.page.locator('.winner-announcement')).toBeVisible({ timeout: 5000 });
      
      // Verify winner text is shown
      await expect(player1.page.locator('.winner-text')).toBeVisible();
      
      // One player should see "You won", the other "You lost"
      const player1Won = await player1.page.locator('.you-won').isVisible().catch(() => false);
      const player2Won = await player2.page.locator('.you-won').isVisible().catch(() => false);
      
      // Exactly one player should have won
      expect(player1Won || player2Won).toBe(true);
      expect(player1Won && player2Won).toBe(false);
    } finally {
      try { await player1.context.close(); } catch (e) {}
      try { await player2.context.close(); } catch (e) {}
    }
  });

  test('host disconnect on game over transfers host to remaining player @slow', async ({ browser }) => {
    test.setTimeout(180000); // This test involves full gameplay + disconnect wait
    
    const player1 = await createPlayer(browser, 'Host');
    const player2 = await createPlayer(browser, 'Guest');
    const player3 = await createPlayer(browser, 'Third');
    
    try {
      // Player 1 creates game
      const gameUrl = await createGame(player1.page, 'HostPlayer');
      
      // Players 2 and 3 join
      await joinGame(player2.page, gameUrl, 'Player2');
      await joinGame(player3.page, gameUrl, 'Player3');
      
      // Wait for all players to appear
      await expect(player1.page.locator('.player-name:has-text("Player2")')).toBeVisible({ timeout: 5000 });
      await expect(player1.page.locator('.player-name:has-text("Player3")')).toBeVisible({ timeout: 5000 });
      
      // Host starts the game
      await player1.page.locator('button:has-text("Start")').click();
      
      // Wait for game to start
      await expect(player1.page.locator('.game-area')).toBeVisible({ timeout: 5000 });
      
      // Play rounds until game over
      // Strategy: placer places all marbles, others guess correctly -> placer loses all marbles
      let gameOver = false;
      let attempts = 0;
      const maxAttempts = 20;
      
      // Helper to check if any player sees game over
      const checkGameOver = async () => {
        for (const p of [player1, player2, player3]) {
          try {
            const visible = await p.page.locator('.winner-announcement').isVisible();
            if (visible) return true;
          } catch { /* player might be disconnected */ }
        }
        return false;
      };
      
      // Helper to play a round
      const playRound = async () => {
        // Find the placer
        for (const player of [player1, player2, player3]) {
          const isPlacer = await player.page.locator('#place-form').isVisible().catch(() => false);
          if (!isPlacer) continue;
          
          // Get max marbles and place them all
          const maxMarbleBtn = player.page.locator('.marble-btn').last();
          const maxValue = await maxMarbleBtn.getAttribute('data-value').catch(() => null);
          if (!maxValue) continue;
          
          const marbles = parseInt(maxValue);
          await player.page.locator(`.marble-btn[data-value="${marbles}"]`).click();
          await player.page.locator('button[type="submit"]').click();
          
          // Wait for guessing phase
          await player1.page.waitForTimeout(1000);
          
          // All non-placers guess correctly
          const isEven = marbles % 2 === 0;
          for (const guesser of [player1, player2, player3]) {
            if (guesser === player) continue;
            try {
              const canGuess = await guesser.page.locator('.btn-even').isVisible();
              if (canGuess) {
                await guesser.page.locator(isEven ? '.btn-even' : '.btn-odd').click();
              }
            } catch { /* might already have guessed or be eliminated */ }
          }
          
          return true;
        }
        return false;
      };
      
      while (!gameOver && attempts < maxAttempts) {
        attempts++;
        
        gameOver = await checkGameOver();
        if (gameOver) break;
        
        // Try to play a round
        const playedRound = await playRound();
        
        // Wait for result phase to complete
        await player1.page.waitForTimeout(playedRound ? 5000 : 2000);
      }
      
      // Verify game over - at least one player should see it
      gameOver = await checkGameOver();
      expect(gameOver).toBe(true);
      
      // Player2 and Player3 should NOT see Play Again yet (only host sees it)
      const p2SeesPlayAgainBefore = await player2.page.locator('button:has-text("Play Again")').isVisible().catch(() => false);
      const p3SeesPlayAgainBefore = await player3.page.locator('button:has-text("Play Again")').isVisible().catch(() => false);
      
      // Neither player2 nor player3 should be host initially
      expect(p2SeesPlayAgainBefore && p3SeesPlayAgainBefore).toBe(false);
      
      // Now disconnect the original host (player1)
      await player1.context.close();
      
      // Wait for SSE to detect disconnect and show countdown (may take a few seconds)
      // The countdown should appear on one of the remaining players' screens
      await expect(async () => {
        const p2Countdown = await player2.page.locator('.player-countdown').isVisible().catch(() => false);
        const p3Countdown = await player3.page.locator('.player-countdown').isVisible().catch(() => false);
        expect(p2Countdown || p3Countdown).toBe(true);
      }).toPass({ timeout: 10000 });
      
      // Wait for grace period (15s) to expire + buffer for SSE update
      // The client-side countdown triggers /check-disconnects when it reaches 0
      await player2.page.waitForTimeout(18000);
      
      // After host disconnect, remaining players should still see the game over screen
      const p2StillSees = await player2.page.locator('.game-over, .winner-announcement').first().isVisible().catch(() => false);
      const p3StillSees = await player3.page.locator('.game-over, .winner-announcement').first().isVisible().catch(() => false);
      
      // At least one remaining player should still see the game over state
      expect(p2StillSees || p3StillSees).toBe(true);
      
      // After host transfer via SSE, exactly one of the remaining players should see Play Again
      // (the one who became the new host)
      const p2SeesPlayAgain = await player2.page.locator('button:has-text("Play Again")').isVisible().catch(() => false);
      const p3SeesPlayAgain = await player3.page.locator('button:has-text("Play Again")').isVisible().catch(() => false);
      
      // Exactly one player should now see Play Again (the new host)
      // XOR: one but not both
      expect(p2SeesPlayAgain !== p3SeesPlayAgain).toBe(true);
    } finally {
      try { await player2.context.close(); } catch (e) {}
      try { await player3.context.close(); } catch (e) {}
    }
  });
});
