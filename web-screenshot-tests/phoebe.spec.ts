import { expect, type Page, test } from '@playwright/test';

const coreScenarios = [
  'Home',
  'HomePlayedRows',
  'FavoritePlaylists',
  'FavoriteArtists',
  'FavoriteAlbums',
  'Library',
  'Playlist',
  'Artist',
  'ArtistRadio',
  'Album',
  'CollectionValues',
  'CollectionItems',
  'Search',
  'Radio',
  'Player',
  'Settings',
  'SignIn',
] as const;

const lightScenarios = ['Home', 'Library', 'Search', 'Player'] as const;
const phoneLightScenarios = [
  ['PlayerBlurredArtworkOn', 'player-blurred-artwork-on'],
  ['PlayerBlurredArtworkOff', 'player-blurred-artwork-off'],
  ['PlayerVisualizerAlchemy', 'player-visualizer-alchemy'],
  ['PlayerVisualizerBattery', 'player-visualizer-battery'],
  ['PlayerVisualizerBarsAndWaves', 'player-visualizer-bars-and-waves'],
  ['PlayerVisualizerBlazingColors', 'player-visualizer-blazing-colors'],
  ['PlayerVisualizerPlenoptic', 'player-visualizer-plenoptic'],
] as const;
const phoneDarkScenarios = [
  ['LibraryFiveColumnGrid', 'library-five-column-grid'],
  ['Radio', 'radio'],
] as const;
const scrollbarScenarios = [
  ['LibraryScrollbar', 'library-scrollbar'],
] as const;
const appearanceDesigns = ['porcelain', 'nocturne', 'brutalist', 'minimalist'] as const;
const appearanceThemes = ['dark', 'light'] as const;
const webDesignScenarios = ['Home', 'Library', 'Album', 'Player', 'Settings', 'Search'] as const;

for (const scenario of coreScenarios) {
  test(`web ${scenario} dark`, async ({ page }) => {
    await openScenario(page, scenario, 'dark');
    await expect(page).toHaveScreenshot(`web-${scenario.toLowerCase()}-dark.png`, {
      animations: 'disabled',
      fullPage: true,
    });
  });
}

for (const scenario of lightScenarios) {
  test(`web ${scenario} light`, async ({ page }) => {
    await openScenario(page, scenario, 'light');
    await expect(page).toHaveScreenshot(`web-${scenario.toLowerCase()}-light.png`, {
      animations: 'disabled',
      fullPage: true,
    });
  });
}

for (const [scenario, slug] of phoneLightScenarios) {
  test(`web phone ${scenario} light`, async ({ page }) => {
    await openScenario(page, scenario, 'light', { width: 430, height: 932 });
    await expect(page).toHaveScreenshot(`web-phone-${slug}-light.png`, {
      animations: 'disabled',
      fullPage: true,
    });
  });
}

for (const [scenario, slug] of phoneDarkScenarios) {
  test(`web phone ${scenario} dark`, async ({ page }) => {
    await openScenario(page, scenario, 'dark', { width: 430, height: 932 });
    await expect(page).toHaveScreenshot(`web-phone-${slug}-dark.png`, {
      animations: 'disabled',
      fullPage: true,
    });
  });
}

for (const [scenario, slug] of scrollbarScenarios) {
  test(`web ${scenario} dark`, async ({ page }) => {
    await openScenario(page, scenario, 'dark', undefined, 1_500);
    await expect(page).toHaveScreenshot(`web-${slug}-dark.png`, {
      animations: 'disabled',
      fullPage: true,
    });
  });
}

for (const design of appearanceDesigns) {
  for (const theme of appearanceThemes) {
    for (const scenario of webDesignScenarios) {
      test(`web ${design} ${scenario} ${theme}`, async ({ page }) => {
        await openScenario(page, scenario, theme, undefined, 750, design);
        await expect(page).toHaveScreenshot(`web-${design}-${scenario.toLowerCase()}-${theme}.png`, {
          animations: 'disabled',
          fullPage: true,
        });
      });
    }
  }
}

test('web brutalist dense library dark', async ({ page }) => {
  await openScenario(page, 'LibraryScrollbar', 'dark', undefined, 1_500, 'brutalist');
  await expect(page).toHaveScreenshot('web-brutalist-library-scrollbar-dark.png', {
    animations: 'disabled',
    fullPage: true,
  });
});

test('web nocturne player queue dark', async ({ page }) => {
  await openScenario(page, 'PlayerUpNextExpanded', 'dark', { width: 430, height: 932 }, 750, 'nocturne');
  await expect(page).toHaveScreenshot('web-phone-nocturne-player-queue-dark.png', {
    animations: 'disabled',
    fullPage: true,
  });
});

async function openScenario(
  page: Page,
  scenario: string,
  theme: 'dark' | 'light',
  viewport = { width: 1365, height: 900 },
  settleMs = 750,
  design = 'default',
) {
  await page.setViewportSize(viewport);
  if (scenario === 'Radio') {
    await page.route(/^https?:\/\//, async route => {
      const requestUrl = new URL(route.request().url());
      if (requestUrl.hostname === '127.0.0.1' || requestUrl.hostname === 'localhost') {
        await route.continue();
      } else {
        await route.abort();
      }
    });
  }
  await page.goto(`/?screenshot=${scenario}&theme=${theme}&design=${design}`, { waitUntil: 'domcontentloaded' });
  await page.locator('canvas').waitFor({ state: 'visible', timeout: 60_000 });
  await page.waitForTimeout(settleMs);
}
