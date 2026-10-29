import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { api, errorMessage, setCsrfToken } from './api';
import CasePage from './CasePage';
import { ErrorBox, Loading } from './components';
import ImportPage, { newImportDraft } from './ImportPage';
import type { Session } from './types';
import { AuditPage, RunPage, RunsPage } from './WorkspacePages';

function useHashRoute() {
  const [hash, setHash] = useState(window.location.hash.slice(1) || '/runs');
  useEffect(() => {
    const update = () => { setHash(window.location.hash.slice(1) || '/runs'); window.scrollTo({ top: 0 }); };
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  const [path, search] = hash.split('?');
  return { path, segments: path.split('/').filter(Boolean), query: new URLSearchParams(search) };
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [checking, setChecking] = useState(true);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [draft, setDraft] = useState(newImportDraft);
  const route = useHashRoute();
  async function checkSession() {
    setChecking(true); setSessionError(null);
    try {
      const current = await api<Session>('/auth/session', {}, false);
      setCsrfToken(current.csrfToken); setSession(current);
    } catch (cause) {
      if (!(cause instanceof Error && 'status' in cause && cause.status === 401)) setSessionError(errorMessage(cause));
    } finally { setChecking(false); }
  }
  useEffect(() => { void checkSession(); }, []);
  useEffect(() => {
    function expire() { setSession(null); setCsrfToken(''); setExpired(true); }
    window.addEventListener('recondesk:session-expired', expire);
    return () => window.removeEventListener('recondesk:session-expired', expire);
  }, []);
  useEffect(() => {
    const title = route.segments[0] === 'import' ? 'Import CSV pair' : route.segments[0] === 'audit' ? 'Audit history' : route.segments[2] === 'cases' ? 'Transaction case' : route.segments[1] ? 'Reconciliation run' : 'Reconciliation runs';
    document.title = `${title} | ReconDesk`;
  }, [route.path]);
  async function logout() {
    if (loggingOut) return;
    setLoggingOut(true); setLogoutError(null);
    try { await api<void>('/auth/logout', { method: 'POST' }); setCsrfToken(''); setSession(null); setExpired(false); setNotice(null); }
    catch (cause) { setLogoutError(errorMessage(cause)); }
    finally { setLoggingOut(false); }
  }
  if (checking) return <div className="session-loading"><div className="wordmark">ReconDesk<span>Transaction reconciliation</span></div><Loading>Checking your session…</Loading></div>;
  if (!session) return <Login expired={expired} sessionError={sessionError} onRetry={checkSession} onLogin={(current) => { setCsrfToken(current.csrfToken); setSession(current); setExpired(false); setSessionError(null); setLogoutError(null); }} />;
  const active = route.segments[0] === 'import' ? 'import' : route.segments[0] === 'audit' ? 'audit' : 'runs';
  const { user } = session;
  return <div className="app-shell">
    <a className="skip-link" href="#main-content" onClick={(event) => { event.preventDefault(); document.getElementById('main-content')?.focus(); }}>Skip to main content</a>
    <aside className="sidebar">
      <a href="#/runs" className="wordmark" aria-label="ReconDesk runs">ReconDesk<span>Transaction reconciliation</span></a>
      <div className="workspace-label">Training workspace</div>
      <nav className="main-nav" aria-label="Workspace">
        <a href="#/runs" className={active === 'runs' ? 'active' : ''} aria-current={active === 'runs' ? 'page' : undefined}><span className="nav-symbol" aria-hidden="true">01</span>Runs</a>
        <a href="#/import" className={active === 'import' ? 'active' : ''} aria-current={active === 'import' ? 'page' : undefined}><span className="nav-symbol" aria-hidden="true">02</span>Import CSV</a>
        <a href="#/audit" className={active === 'audit' ? 'active' : ''} aria-current={active === 'audit' ? 'page' : undefined}><span className="nav-symbol" aria-hidden="true">03</span>Audit history</a>
      </nav>
      <div className="sidebar-context"><span className="source-pair-inline" aria-hidden="true">I <span>/</span> P</span><p>Compare the sources.<br />Record the explanation.<br />Review the decision.</p></div>
      <div className="account"><div className="account-name">{user.name}</div><div className="account-role">{user.role === 'OPERATOR' ? 'Operator' : 'Reviewer'}</div><button className="button text-button" onClick={logout} disabled={loggingOut}>{loggingOut ? 'Signing out…' : 'Sign out'}</button></div>
    </aside>
    <div className="workspace-main">
      <header className="workspace-header"><span>Source reconciliation</span><span>{user.role === 'OPERATOR' ? 'Import & investigation' : 'Independent review'}</span></header>
      <main id="main-content" tabIndex={-1}>
        {logoutError && <ErrorBox message={logoutError} onRetry={logout} />}
        {notice && <div className="notice success global-notice" role="status"><p>{notice}</p><button type="button" className="button text-button" onClick={() => setNotice(null)}>Dismiss</button></div>}
        {active === 'import' ? <ImportPage user={user} draft={draft} setDraft={setDraft} onImported={(run) => { setNotice(`“${run.name}” was imported and compared successfully.`); window.location.hash = `/runs/${run.id}`; }} /> : active === 'audit' ? <AuditPage query={route.query} /> : route.segments.length === 4 && route.segments[2] === 'cases' ? <CasePage key={route.segments[3]} runId={route.segments[1]} caseId={route.segments[3]} query={route.query} user={user} /> : route.segments.length === 2 ? <RunPage key={route.segments[1]} runId={route.segments[1]} query={route.query} /> : <RunsPage query={route.query} user={user} />}
      </main>
      <footer className="workspace-footer"><span>ReconDesk · Synthetic data training application</span><span>Approval records an investigation decision.</span></footer>
    </div>
  </div>;
}

function Login({ onLogin, expired, sessionError, onRetry }: { onLogin: (session: Session) => void; expired: boolean; sessionError: string | null; onRetry: () => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setError(null);
    try { onLogin(await api<Session>('/auth/login', { method: 'POST', body: JSON.stringify({ email: email.trim(), password }) }, false)); }
    catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  }
  return <div className="login-page">
    <div className="login-brand"><div className="wordmark">ReconDesk<span>Transaction reconciliation</span></div></div>
    <main className="login-layout">
      <div className="login-context"><div className="login-source-title"><span className="source-mark large" aria-hidden="true">I</span><span className="source-connector" aria-hidden="true">/</span><span className="source-mark large" aria-hidden="true">P</span></div><h1>Compare sources.<br />Review exceptions.</h1><p>Compare transaction files, investigate exceptions and record a separate reviewer’s decision.</p><dl className="login-workflow"><div><dt>Operator</dt><dd>Import a CSV pair and propose an explanation for open cases.</dd></div><div><dt>Reviewer</dt><dd>Check the evidence, then approve or return the proposal.</dd></div></dl><p className="training-caption">Local training workspace using synthetic transactions.</p></div>
      <section className="login-form sheet" aria-labelledby="login-heading"><h2 id="login-heading">Sign in to your workspace</h2><p className="muted">Use the local account created during setup.</p>{expired && <div className="notice" role="status"><strong>Your session expired</strong><p>Sign in again to continue. An uncertain import keeps its original files and retry identity in this tab.</p></div>}{sessionError && <ErrorBox message={sessionError} onRetry={onRetry} />}
        <form className="stack-form" onSubmit={submit}><div className="field"><label htmlFor="email">Email address</label><input id="email" type="email" required autoComplete="username" value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy} placeholder="operator@recondesk.test" /></div><div className="field"><label htmlFor="password">Password</label><input id="password" type="password" required autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></div>{error && <div className="notice error" role="alert"><p>{error}</p></div>}<button className="button primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button></form>
        <div className="demo-accounts"><h3>Synthetic demo accounts</h3><button className="account-preset" disabled={busy} onClick={() => { setEmail('operator@recondesk.test'); document.getElementById('password')?.focus(); }}><strong>Operator</strong><span>operator@recondesk.test</span></button><button className="account-preset" disabled={busy} onClick={() => { setEmail('reviewer@recondesk.test'); document.getElementById('password')?.focus(); }}><strong>Reviewer</strong><span>reviewer@recondesk.test</span></button><p className="field-help">The password is generated locally by the setup script. It is never prefilled here.</p></div>
      </section>
    </main>
    <footer className="login-footer">Source rows stay unchanged. Approval records an investigation decision and does not move money.</footer>
  </div>;
}
