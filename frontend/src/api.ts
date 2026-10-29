export class ApiError extends Error {
  status: number;
  code: string;
  details: unknown;
  constructor(status: number, message: string, code = 'REQUEST_FAILED', details?: unknown) {
    super(message); this.name = 'ApiError'; this.status = status; this.code = code; this.details = details;
  }
}

let csrfToken = '';
export function setCsrfToken(token: string) { csrfToken = token; }

export async function api<T>(path: string, options: RequestInit = {}, announceExpiry = true): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  if (options.method && options.method !== 'GET') headers.set('X-CSRF-Token', csrfToken);
  let response: Response;
  try { response = await fetch(`/api${path}`, { ...options, credentials: 'same-origin', headers }); }
  catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'The server could not be reached. Check the connection and try again.', 'NETWORK_ERROR');
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string; code?: string; details?: unknown };
    if (response.status === 401 && announceExpiry) window.dispatchEvent(new Event('recondesk:session-expired'));
    throw new ApiError(response.status, body.message || `The request failed (${response.status}).`, body.code, body.details);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'The request could not be completed. Try again.';
}
export function resultUncertain(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
}

export async function downloadExport(runId: string, name: string): Promise<void> {
  let response: Response;
  try { response = await fetch(`/api/runs/${encodeURIComponent(runId)}/export.csv`, { credentials: 'same-origin' }); }
  catch { throw new ApiError(0, 'The export could not be downloaded. Check the connection and try again.'); }
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event('recondesk:session-expired'));
    const body = await response.json().catch(() => ({})) as { message?: string };
    throw new ApiError(response.status, body.message || 'The export could not be downloaded.');
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url; link.download = `${name.replace(/[^a-zA-Z0-9._-]/g, '_') || 'reconciliation'}-results.csv`;
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
