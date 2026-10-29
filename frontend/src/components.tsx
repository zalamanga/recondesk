import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { api, errorMessage } from './api';
import type { Page } from './types';
import { stateLabels } from './utils';
import type { CaseState } from './types';

export function useResource<T>(path: string) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null); setData(null);
    api<T>(path, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setData(result);
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) setError(errorMessage(cause));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [path, revision]);
  return { data, setData, loading, error, refresh: () => setRevision((value) => value + 1) };
}

export function Loading({ children = 'Loading workspace…' }: { children?: ReactNode }) {
  return <div className="state-box" role="status"><span className="loading-line" aria-hidden="true" />{children}</div>;
}
export function ErrorBox({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return <div className="notice error" role="alert"><div><strong>Unable to complete the request</strong><p>{message}</p></div>{onRetry && <button className="button secondary" onClick={onRetry}>Try again</button>}</div>;
}
export function Empty({ title, children }: { title: string; children: ReactNode }) {
  return <div className="empty-state"><div className="empty-ledger" aria-hidden="true"><i /><i /><i /></div><h2>{title}</h2><div className="muted">{children}</div></div>;
}
export function StateLabel({ state }: { state: CaseState }) {
  return <span className={`case-state state-${state.toLowerCase()}`}>{stateLabels[state]}</span>;
}
export function Pagination({ page, onChange, label = 'results' }: { page: Page<unknown>; onChange: (page: number) => void; label?: string }) {
  if (page.total === 0) return null;
  const pages = Math.max(1, Math.ceil(page.total / page.pageSize));
  return <nav className="pagination" aria-label={`${label} pages`}>
    <p>{page.page * page.pageSize + 1} to {Math.min((page.page + 1) * page.pageSize, page.total)} of {page.total} {label}</p>
    <div className="pagination-controls"><button className="button secondary" disabled={page.page === 0} onClick={() => onChange(page.page - 1)}>Previous</button><span>Page {page.page + 1} of {pages}</span><button className="button secondary" disabled={page.page + 1 >= pages} onClick={() => onChange(page.page + 1)}>Next</button></div>
  </nav>;
}
export function PageHeading({ title, description, action }: { title: string; description?: ReactNode; action?: ReactNode }) {
  return <div className="page-heading"><div><h1>{title}</h1>{description && <div className="page-description">{description}</div>}</div>{action && <div className="heading-action">{action}</div>}</div>;
}
