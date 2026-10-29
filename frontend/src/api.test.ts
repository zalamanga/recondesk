import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, setCsrfToken } from './api';

afterEach(() => { vi.unstubAllGlobals(); setCsrfToken(''); });
describe('authenticated API requests', () => {
  it('sends the session CSRF token on mutations without modifying exact decimal strings', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ amount: '999999999999.99' }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock); setCsrfToken('test-session-csrf');
    const result = await api<{ amount: string }>('/cases/case/proposals', { method: 'POST', body: JSON.stringify({ expectedVersion: 2, note: 'An evidence-backed explanation.' }) });
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/cases/case/proposals');
    expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('test-session-csrf');
    expect(new Headers(options.headers).get('Content-Type')).toBe('application/json');
    expect(options.credentials).toBe('same-origin');
    expect(result.amount).toBe('999999999999.99');
  });
  it('leaves the multipart boundary to fetch and preserves the caller supplied import identity', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 201 }));
    vi.stubGlobal('fetch', fetchMock);
    const form = new FormData(); form.set('name', 'Source pair');
    await api('/runs', { method: 'POST', body: form, headers: { 'Idempotency-Key': 'retained-identity' } });
    const options = fetchMock.mock.calls[0][1] as RequestInit;
    expect(new Headers(options.headers).has('Content-Type')).toBe(false);
    expect(new Headers(options.headers).get('Idempotency-Key')).toBe('retained-identity');
    expect(options.body).toBe(form);
  });
  it('retains structured server errors and announces expired authenticated sessions', async () => {
    const events = new EventTarget(); const expire = vi.fn(); events.addEventListener('recondesk:session-expired', expire);
    vi.stubGlobal('window', events);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'SESSION_EXPIRED', message: 'Please sign in again.' }), { status: 401 })));
    await expect(api('/runs')).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED', message: 'Please sign in again.' });
    expect(expire).toHaveBeenCalledOnce();
  });
  it('represents a dropped response as an uncertain network result', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(api('/runs', { method: 'POST', body: new FormData() })).rejects.toBeInstanceOf(ApiError);
    await expect(api('/runs')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });
});
