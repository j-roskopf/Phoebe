import { expect, test } from '@playwright/test';

type PhoebeE2eResult = { passed: boolean; message: string };

async function waitForPhoebeE2eResult(page, timeout = 60_000): Promise<PhoebeE2eResult> {
  await page.waitForFunction(
    () => (window as unknown as { phoebeE2eResults?: PhoebeE2eResult }).phoebeE2eResults !== undefined,
    undefined,
    { timeout },
  );
  return page.evaluate(() => (window as unknown as { phoebeE2eResults: PhoebeE2eResult }).phoebeE2eResults);
}

for (const provider of ['plex', 'jellyfin', 'emby', 'navidrome', 'musicassistant'] as const) {
  test(`web provider ${provider} adapter smoke`, async ({ page }) => {
    await page.goto(`/?e2e=providerSmoke:${provider}`, { waitUntil: 'domcontentloaded' });
    const results = await waitForPhoebeE2eResult(page);
    expect(results.passed, results.message).toBe(true);
    expect(results.message).toContain('provider adapter smoke passed');
  });
}

test('web all provider adapter smoke', async ({ page }) => {
  await page.goto('/?e2e=providerSmoke:all', { waitUntil: 'domcontentloaded' });
  const results = await waitForPhoebeE2eResult(page);
  expect(results.passed, results.message).toBe(true);
  expect(results.message).toContain('all provider adapter smoke passed');
});

test('web local library indexes mp3 and starts playback', async ({ page }) => {
  await page.goto('/?e2e=localLibrary', { waitUntil: 'domcontentloaded' });
  const results = await waitForPhoebeE2eResult(page);
  expect(results.passed, results.message).toBe(true);
  expect(results.message).toContain('playback started');
});

test('web local playlist export formats m3u8 text and csv', async ({ page }) => {
  await page.goto('/?e2e=localPlaylist', { waitUntil: 'domcontentloaded' });
  const results = await waitForPhoebeE2eResult(page);
  expect(results.passed, results.message).toBe(true);
  expect(results.message).toContain('m3u8');
  expect(results.message).toContain('csv');
});

test('web chromecast mock connects and loads a remote stream', async ({ page }) => {
  await page.goto('/?e2e=castMock', { waitUntil: 'domcontentloaded' });
  const results = await waitForPhoebeE2eResult(page);
  expect(results.passed, results.message).toBe(true);
  expect(results.message).toContain('mock Chromecast connected');
});

test('web chromecast bootstrap tolerates missing chrome.cast namespace', async ({ page }) => {
  const pageErrors: string[] = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.route('https://www.gstatic.com/cv/js/sender/**', route => route.abort());
  await page.route('**/phoebe.js', route => route.abort());

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  const options = await page.evaluate(() => {
    let observedOptions: { receiverApplicationId?: string; autoJoinPolicy?: string } | null = null;
    const testWindow = window as unknown as {
      __onGCastApiAvailable: (isAvailable: boolean) => void;
      cast: unknown;
      chrome: unknown;
    };
    testWindow.cast = {
      framework: {
        CastContext: {
          getInstance: () => ({
            setOptions: (nextOptions: { receiverApplicationId?: string; autoJoinPolicy?: string }) => {
              observedOptions = nextOptions;
            },
          }),
        },
      },
    };
    testWindow.chrome = {};

    testWindow.__onGCastApiAvailable(true);

    return observedOptions;
  });

  expect(pageErrors).toEqual([]);
  expect(options).toEqual({ receiverApplicationId: 'CC1AD845' });
});

test('web local playback regression starts real browser audio after tap', async ({ page }) => {
  await page.goto('/?e2e=localPlaybackRegression', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(
    () => (window as unknown as { phoebeE2eReady?: boolean }).phoebeE2eReady === true,
    undefined,
    { timeout: 60_000 },
  );
  await page.locator('#phoebe-web-playback-regression-play').click();
  const results = await waitForPhoebeE2eResult(page, 10_000);
  expect(results.passed, results.message).toBe(true);
  expect(results.message).toContain('web local playback started');
});

for (const path of [
  '/settings',
  '/search',
  '/player',
  '/favorites/artists',
  '/artist/modern-baseball/album/youre-gonna-miss-it-all',
  '/playlists/road-trip',
]) {
  test(`web path ${path} loads and reloads`, async ({ page }) => {
    await seedLocalSource(page);
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    await waitForPhoebeCanvas(page);
    await expect(page).toHaveURL(new RegExp(`${escapeRegExp(path)}$`));

    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForPhoebeCanvas(page);
    await expect(page).toHaveURL(new RegExp(`${escapeRegExp(path)}$`));
  });
}

for (const { path, expected } of [
  { path: '/', expected: '/signin' },
  { path: '/library', expected: '/signin' },
  { path: '/settings', expected: '/settings' },
  { path: '/artist/modern-baseball', expected: '/signin' },
] as const) {
  test(`web path ${path} resolves without sources`, async ({ page }) => {
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    await waitForPhoebeCanvas(page);
    await expect(page).toHaveURL(new RegExp(`${escapeRegExp(expected)}$`));

    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForPhoebeCanvas(page);
    await expect(page).toHaveURL(new RegExp(`${escapeRegExp(expected)}$`));
  });
}

test('web browser history popstate keeps routes in sync', async ({ page }) => {
  await seedLocalSource(page);
  await page.goto('/settings', { waitUntil: 'domcontentloaded' });
  await waitForPhoebeCanvas(page);
  const initialHistoryLength = await page.evaluate(() => window.history.length);

  await page.evaluate(() => {
    window.history.pushState(null, '', '/search');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page).toHaveURL(/\/search$/);
  await waitForPhoebeCanvas(page);

  const pushedHistoryLength = await page.evaluate(() => window.history.length);
  expect(pushedHistoryLength).toBeLessThanOrEqual(initialHistoryLength + 1);

  await page.goBack();
  await expect(page).toHaveURL(/\/settings$/);
  await waitForPhoebeCanvas(page);

  await page.goForward();
  await expect(page).toHaveURL(/\/search$/);
  await waitForPhoebeCanvas(page);
});

async function waitForPhoebeCanvas(page) {
  await page.locator('canvas').waitFor({ state: 'visible', timeout: 60_000 });
  await page.waitForTimeout(250);
}

async function seedLocalSource(page) {
  await page.addInitScript(() => {
    // Match PhoebeAppDataRevision so startup does not wipe the seeded local folder.
    localStorage.setItem('phoebe:phoebe.app_data_revision', '2');
    localStorage.setItem('phoebe:phoebe-debug.app_data_revision', '2');
    localStorage.setItem(
      'phoebe:media_sources.json',
      JSON.stringify({
        localFolders: [
          {
            id: 'web-route-test-folder',
            rootUri: 'phoebe-web-folder://route-test/Music',
            label: 'Route Test Music',
            enabled: true,
          },
        ],
      }),
    );
  });
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
