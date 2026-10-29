import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import path from 'node:path';
import { readFile } from 'node:fs/promises';
import type { Run } from '../src/types';

const password = process.env.RECON_DEMO_PASSWORD;
const baseURL = process.env.RECON_BASE_URL || 'http://127.0.0.1:8096';
test.skip(!password, 'Set RECON_DEMO_PASSWORD to the local generated password before running browser tests.');
async function login(page: Page, role: 'operator' | 'reviewer') {
  await page.goto('/');
  await page.getByLabel('Email address').fill(`${role}@recondesk.test`);
  await page.getByLabel('Password', { exact: true }).fill(password!);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Workspace', exact: true })).toBeVisible();
}
async function importPair(page: Page, name: string) {
  await page.getByRole('navigation', { name: 'Workspace', exact: true }).getByRole('link', { name: 'Import CSV', exact: true }).click();
  await page.getByLabel('Run name', { exact: true }).fill(name);
  await page.getByLabel('Internal source', { exact: true }).setInputFiles(path.resolve('public/samples/internal.csv'));
  await page.getByLabel('Partner source', { exact: true }).setInputFiles(path.resolve('public/samples/partner.csv'));
  await page.getByRole('button', { name: 'Import and compare', exact: true }).click();
}

test('an uncertain import retries the same identity and completes rejection, resubmission and approval', async ({ browser }) => {
  const operatorContext = await browser.newContext({ baseURL });
  const reviewerContext = await browser.newContext({ baseURL });
  const operator = await operatorContext.newPage();
  const reviewer = await reviewerContext.newPage();
  const identities: string[] = [];
  let imported: Run | null = null;
  let loseFirstResponse = true;
  const name = `Browser workflow ${Date.now()}`;
  const internalBytes = await readFile(path.resolve('public/samples/internal.csv'));
  const partnerBytes = await readFile(path.resolve('public/samples/partner.csv'));
  await login(operator, 'operator');
  await operator.route('**/api/runs', async (route) => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    identities.push(route.request().headers()['idempotency-key']);
    if (loseFirstResponse) {
      loseFirstResponse = false;
      const originalHeaders = await route.request().allHeaders();
      const headers = {
        Origin: originalHeaders.origin,
        'X-CSRF-Token': originalHeaders['x-csrf-token'],
        'Idempotency-Key': originalHeaders['idempotency-key'],
      };
      // Chromium's intercepted multipart body omits file bytes. Send the exact
      // selected fixture bytes to the real server before dropping the browser response.
      const response = await operatorContext.request.post(route.request().url(), {
        headers,
        multipart: {
          name,
          internalFile: { name: 'internal.csv', mimeType: 'text/csv', buffer: internalBytes },
          partnerFile: { name: 'partner.csv', mimeType: 'text/csv', buffer: partnerBytes },
        },
      });
      if (response.status() !== 201) {
        const error = await response.json().catch(() => ({})) as { code?: string; message?: string };
        throw new Error(`Import returned ${response.status()}: ${error.code || 'UNKNOWN'}: ${error.message || 'No JSON error message'}`);
      }
      imported = await response.json() as Run;
      await route.abort('failed');
    } else { await route.continue(); }
  });
  await importPair(operator, name);
  await expect(operator.getByText('Import outcome is not confirmed', { exact: true })).toBeVisible();
  await expect(operator.getByLabel('Run name', { exact: true })).toBeDisabled();
  await expect(operator.getByLabel('Internal source', { exact: true })).toBeDisabled();
  const replayResponse = operator.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/runs');
  await operator.getByRole('button', { name: 'Retry the same CSV pair', exact: true }).click();
  expect((await replayResponse).status()).toBe(200);
  await expect(operator.getByRole('heading', { name, exact: true })).toBeVisible();
  expect(identities).toHaveLength(2);
  expect(identities[0]).toMatch(/^[0-9a-f-]{36}$/);
  expect(identities[1]).toBe(identities[0]);
  const committed = imported as Run | null;
  expect(committed).not.toBeNull();
  expect(operator.url()).toContain(committed!.id);
  await expect(operator.getByRole('button', { name: 'All cases 10', exact: true })).toBeVisible();
  await operator.getByRole('link', { name: 'DEMO-DIFF-AMOUNT', exact: true }).click();
  await expect(operator.getByText('50,000.00', { exact: true })).toBeVisible();
  await expect(operator.getByText('49,900.00', { exact: true })).toBeVisible();
  const staleOperator = await operatorContext.newPage();
  await staleOperator.goto(operator.url());
  await staleOperator.getByLabel('Investigation note', { exact: true }).fill('This stale proposal must never overwrite another operator action.');
  await operator.getByLabel('Investigation note', { exact: true }).fill('Initial explanation: compare the original settlement evidence.');
  await operator.getByRole('button', { name: 'Submit for review', exact: true }).click();
  await expect(operator.getByText('Proposal submitted. A different reviewer can now decide it.', { exact: true })).toBeVisible();
  await staleOperator.getByRole('button', { name: 'Submit for review', exact: true }).click();
  await expect(staleOperator.getByText('This case changed while you were working. Refresh it to read the current proposal and status before continuing.', { exact: true })).toBeVisible();
  await expect(staleOperator.getByLabel('Investigation note', { exact: true })).toBeDisabled();
  await staleOperator.getByRole('button', { name: 'Refresh case', exact: true }).click();
  await expect(staleOperator.getByText('Read-only at this stage', { exact: true })).toBeVisible();
  await staleOperator.close();
  const caseUrl = operator.url();
  await login(reviewer, 'reviewer');
  await reviewer.goto(caseUrl);
  await reviewer.getByLabel('Reject Return to the operator').check();
  await reviewer.getByLabel('Decision note', { exact: true }).fill('Please include the supporting settlement reference before approval.');
  await reviewer.getByRole('button', { name: 'Reject proposal', exact: true }).click();
  await expect(reviewer.getByText('Proposal rejected. The case is open for a new proposal.', { exact: true })).toBeVisible();
  await operator.getByRole('button', { name: 'Refresh case status', exact: true }).click();
  await expect(operator.getByRole('heading', { name: 'Submit a revised explanation', exact: true })).toBeVisible();
  await operator.getByLabel('Investigation note', { exact: true }).fill('Revised explanation: original source evidence confirms the documented difference.');
  await operator.getByRole('button', { name: 'Submit for review', exact: true }).click();
  await expect(operator.getByText('Proposal submitted. A different reviewer can now decide it.', { exact: true })).toBeVisible();
  await reviewer.getByRole('button', { name: 'Refresh case status', exact: true }).click();
  await reviewer.getByLabel('Approve Resolve the investigation').check();
  await reviewer.getByLabel('Decision note', { exact: true }).fill('Reviewed the original evidence and approve this investigation explanation.');
  await reviewer.getByRole('button', { name: 'Approve proposal', exact: true }).click();
  await expect(reviewer.getByText('Proposal approved. The case is resolved.', { exact: true })).toBeVisible();
  await reviewer.getByRole('button', { name: 'Read the decision', exact: true }).click();
  await expect(reviewer.getByRole('heading', { name: 'Rejected explanation', exact: true })).toBeVisible();
  await expect(reviewer.getByRole('heading', { name: 'Approved explanation', exact: true })).toBeVisible();
  await reviewer.getByRole('tab', { name: /^History/ }).focus();
  await reviewer.keyboard.press('ArrowLeft');
  await expect(reviewer.getByRole('tab', { name: 'Source comparison', exact: true })).toHaveAttribute('aria-selected', 'true');
  await reviewer.keyboard.press('ArrowRight');
  await expect(reviewer.getByRole('tab', { name: /^History/ })).toHaveAttribute('aria-selected', 'true');
  await reviewer.getByRole('link', { name: `Back to ${name}`, exact: true }).click();
  await expect(reviewer.getByRole('button', { name: 'Resolved 1', exact: true })).toBeVisible();
  await reviewer.getByRole('button', { name: 'Resolved 1', exact: true }).click();
  await expect(reviewer.getByRole('link', { name: 'DEMO-DIFF-AMOUNT', exact: true })).toBeVisible();
  const downloadPromise = reviewer.waitForEvent('download');
  await reviewer.getByRole('button', { name: 'Export complete run', exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toContain('-results.csv');
  await reviewer.getByRole('navigation', { name: 'Workspace', exact: true }).getByRole('link', { name: 'Audit history', exact: true }).click();
  await expect(reviewer.getByRole('table')).toBeVisible();
  await reviewerContext.close(); await operatorContext.close();
});

test('narrow layout, keyboard tabs, samples and reviewer import restrictions', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, 'reviewer');
  await page.getByRole('navigation', { name: 'Workspace', exact: true }).getByRole('link', { name: 'Import CSV', exact: true }).click();
  await expect(page.getByText('Reviewer access', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Import and compare', exact: true })).toBeDisabled();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('link', { name: 'Download internal.csv CSV', exact: true }).click();
  expect((await downloadPromise).suggestedFilename()).toBe('internal.csv');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace', exact: true })).toBeVisible();
  await page.getByLabel('Email address', { exact: true }).focus();
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Password', { exact: true })).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('an invalid partner file creates no partial run and a corrected pair gets a new identity', async ({ page }) => {
  await login(page, 'operator');
  const beforeResponse = await page.request.get('/api/runs?page=0');
  expect(beforeResponse.ok()).toBe(true);
  const before = await beforeResponse.json() as { total: number };
  const identities: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/runs') identities.push(request.headers()['idempotency-key']);
  });
  await page.getByRole('navigation', { name: 'Workspace', exact: true }).getByRole('link', { name: 'Import CSV', exact: true }).click();
  const name = `Atomic validation ${Date.now()}`;
  await page.getByLabel('Run name', { exact: true }).fill(name);
  await page.getByLabel('Internal source', { exact: true }).setInputFiles(path.resolve('public/samples/internal.csv'));
  await page.getByLabel('Partner source', { exact: true }).setInputFiles({ name: 'invalid.csv', mimeType: 'text/csv', buffer: Buffer.from('reference,date,amount,currency\nBAD,2026-01-15,-1.00,IDR\n') });
  await page.getByRole('button', { name: 'Import and compare', exact: true }).click();
  await expect(page.getByText('Import could not be completed', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Run name', { exact: true })).toBeEnabled();
  const after = await (await page.request.get('/api/runs?page=0')).json() as { total: number };
  expect(after.total).toBe(before.total);
  await page.getByLabel('Partner source', { exact: true }).setInputFiles(path.resolve('public/samples/partner.csv'));
  await page.getByRole('button', { name: 'Import and compare', exact: true }).click();
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  expect(identities).toHaveLength(2);
  expect(identities[0]).not.toBe(identities[1]);
});
