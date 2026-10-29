import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { downloadExport, errorMessage } from './api';
import { Empty, ErrorBox, Loading, PageHeading, Pagination, StateLabel, useResource } from './components';
import type { AuditItem, CaseSummary, Page, Run, User } from './types';
import { actionLabel, classificationLabels, dateTime, money, stateLabels } from './utils';

export function RunsPage({ query, user }: { query: URLSearchParams; user: User }) {
  const pageNumber = pageFromQuery(query);
  const resource = useResource<Page<Run>>(`/runs?page=${pageNumber}`);
  return <>
    <PageHeading title="Reconciliation runs" description="Open a source pair to investigate its exceptions and follow review decisions." action={user.role === 'OPERATOR' && <a className="button primary" href="#/import">Import CSV pair</a>} />
    <section className="sheet" aria-labelledby="runs-heading">
      <div className="section-bar"><h2 id="runs-heading">Run register</h2><button className="button text-button" onClick={resource.refresh} disabled={resource.loading}>Refresh register</button></div>
      {resource.loading ? <Loading>Loading reconciliation runs…</Loading> : resource.error ? <ErrorBox message={resource.error} onRetry={resource.refresh} /> : resource.data && resource.data.items.length === 0 ? <Empty title="Your first source pair starts here"><p>Import an internal ledger and a partner statement to create a reconciliation run.</p>{user.role === 'OPERATOR' ? <a className="button primary" href="#/import">Import CSV pair</a> : <p>An operator can import files. Your reviewer account can read runs and decide proposals.</p>}</Empty> : resource.data && <>
        <div className="table-scroll" role="region" aria-label="Reconciliation run register" tabIndex={0}><table className="runs-table"><thead><tr><th scope="col">Run / created</th><th scope="col" className="numeric">Sources</th><th scope="col" className="numeric">Cases</th><th scope="col" className="numeric">Open</th><th scope="col" className="numeric">Awaiting review</th><th scope="col" className="numeric">Resolved</th></tr></thead><tbody>{resource.data.items.map((run) => <tr key={run.id}><th scope="row"><a className="row-link" href={`#/runs/${run.id}`}>{run.name}</a><span className="row-meta">{dateTime(run.createdAt)} · {run.createdBy.name}</span></th><td className="numeric"><span>{run.internalCount} / {run.partnerCount}</span><span className="row-meta">internal / partner</span></td><td className="numeric">{run.caseCount}<span className="row-meta">{run.matchedCount} matched</span></td><td className="numeric count-open">{run.openCount}</td><td className="numeric">{run.pendingCount}</td><td className="numeric">{run.resolvedCount}</td></tr>)}</tbody></table></div>
        <Pagination page={resource.data} label="runs" onChange={(page) => { window.location.hash = `/runs?page=${page}`; }} />
      </>}
    </section>
    <div className="register-footnote"><span className="source-pair-inline" aria-hidden="true">I <span>/</span> P</span><p>Each run keeps both validated sources. Investigation decisions record an explanation; they do not change source transactions.</p></div>
  </>;
}

export function RunPage({ runId, query }: { runId: string; query: URLSearchParams }) {
  const run = useResource<Run>(`/runs/${encodeURIComponent(runId)}`);
  const state = query.get('state') || 'ALL';
  const classification = query.get('classification') || 'ALL';
  const search = query.get('search') || '';
  const pageNumber = pageFromQuery(query);
  const [searchInput, setSearchInput] = useState(search);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportDone, setExportDone] = useState(false);
  const caseQuery = new URLSearchParams({ page: String(pageNumber), state, classification, search });
  const cases = useResource<Page<CaseSummary>>(`/runs/${encodeURIComponent(runId)}/cases?${caseQuery}`);
  useEffect(() => { setSearchInput(search); }, [search]);
  function filter(change: Record<string, string>) {
    const next = new URLSearchParams({ state, classification, search, page: '0', ...change });
    window.location.hash = `/runs/${runId}?${next}`;
  }
  async function exportCsv() {
    if (!run.data || exporting) return;
    setExporting(true); setExportError(null); setExportDone(false);
    try { await downloadExport(runId, run.data.name); setExportDone(true); }
    catch (cause) { setExportError(errorMessage(cause)); }
    finally { setExporting(false); }
  }
  if (run.loading) return <Loading>Loading source pair…</Loading>;
  if (run.error || !run.data) return <ErrorBox message={run.error || 'The run could not be found.'} onRetry={run.refresh} />;
  const data = run.data;
  return <>
    <a className="back-link" href="#/runs">All runs</a>
    <PageHeading title={data.name} description={<><span>{dateTime(data.createdAt)} · {data.createdBy.name}</span><span className="run-source-counts">Internal: {data.internalCount} rows <span aria-hidden="true">/</span> Partner: {data.partnerCount} rows</span></>} action={<button className="button secondary" onClick={exportCsv} disabled={exporting}>{exporting ? 'Preparing export…' : 'Export complete run'}</button>} />
    {exportError && <ErrorBox message={exportError} onRetry={exportCsv} />}
    {exportDone && <p role="status" className="inline-success">The complete run CSV is ready in your downloads.</p>}
    <div className="run-counts" aria-label="Run totals">
      <Count label="All cases" count={data.caseCount} active={state === 'ALL'} onClick={() => filter({ state: 'ALL' })} />
      <Count label="Open" count={data.openCount} active={state === 'OPEN'} onClick={() => filter({ state: 'OPEN' })} />
      <Count label="Awaiting review" count={data.pendingCount} active={state === 'PENDING'} onClick={() => filter({ state: 'PENDING' })} />
      <Count label="Resolved" count={data.resolvedCount} active={state === 'RESOLVED'} onClick={() => filter({ state: 'RESOLVED' })} />
      <Count label="Matched" count={data.matchedCount} active={state === 'MATCHED'} onClick={() => filter({ state: 'MATCHED' })} />
    </div>
    <section className="sheet" aria-labelledby="cases-heading">
      <div className="section-bar"><h2 id="cases-heading">Transaction cases</h2><button className="button text-button" disabled={cases.loading || run.loading} onClick={() => { cases.refresh(); run.refresh(); }}>Refresh run</button></div>
      <form className="case-filters" onSubmit={(event: FormEvent) => { event.preventDefault(); filter({ search: searchInput.trim() }); }}>
        <div className="field search-field"><label htmlFor="case-search">Reference</label><div className="search-control"><input id="case-search" value={searchInput} maxLength={64} onChange={(event) => setSearchInput(event.target.value)} placeholder="Find a reference" /><button className="button secondary" type="submit">Search</button></div></div>
        <div className="field"><label htmlFor="state-filter">Investigation status</label><select id="state-filter" value={state} onChange={(event) => filter({ state: event.target.value })}><option value="ALL">All statuses</option>{Object.entries(stateLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></div>
        <div className="field"><label htmlFor="classification-filter">Comparison result</label><select id="classification-filter" value={classification} onChange={(event) => filter({ classification: event.target.value })}><option value="ALL">All results</option>{Object.entries(classificationLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></div>
        {(search || state !== 'ALL' || classification !== 'ALL') && <button type="button" className="button text-button clear-filters" onClick={() => filter({ state: 'ALL', classification: 'ALL', search: '' })}>Clear filters</button>}
      </form>
      {cases.loading ? <Loading>Comparing case results…</Loading> : cases.error ? <ErrorBox message={cases.error} onRetry={cases.refresh} /> : cases.data && cases.data.items.length === 0 ? <Empty title="No cases match these filters"><p>Change the status, comparison result or reference to see more cases.</p><button className="button secondary" onClick={() => filter({ state: 'ALL', classification: 'ALL', search: '' })}>Show all cases</button></Empty> : cases.data && <>
        <div className="table-scroll" role="region" aria-label="Transaction comparison results" tabIndex={0}><table className="cases-table"><thead><tr><th scope="col">Reference</th><th scope="col">Comparison result</th><th scope="col" className="numeric">Internal amount</th><th scope="col" className="numeric">Partner amount</th><th scope="col" className="numeric">Delta</th><th scope="col">Status</th></tr></thead><tbody>{cases.data.items.map((item) => <tr key={item.id}><th scope="row"><a className="row-link reference" href={`#/runs/${runId}/cases/${item.id}?${caseQuery}`}>{item.reference}</a><span className="row-meta">{item.internalCount} internal / {item.partnerCount} partner</span></th><td><span>{classificationLabels[item.classification]}</span>{item.differences.length > 0 && <span className="row-meta">{item.differences.map((value) => actionLabel(value)).join(', ')}</span>}</td><td className="numeric amount">{sourceAmount(item.internalAmount, item.internalCount)}</td><td className="numeric amount">{sourceAmount(item.partnerAmount, item.partnerCount)}</td><td className={`numeric amount ${item.delta && !/^0\.00$/.test(item.delta) ? 'delta-value' : ''}`}>{money(item.delta)}</td><td><StateLabel state={item.state} /></td></tr>)}</tbody></table></div>
        <Pagination page={cases.data} label="cases" onChange={(page) => filter({ page: String(page) })} />
      </>}
    </section>
    <p className="table-note">Amounts are shown as supplied by each source. Open a case to compare dates, currencies and every duplicate row. A missing source contributes zero to the delta. Duplicates and different currencies have no comparable delta.</p>
  </>;
}
function sourceAmount(amount: string | null, count: number) { return count === 0 ? 'No row' : count > 1 ? 'Multiple rows' : money(amount); }
function Count({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return <button className={`run-count ${active ? 'active' : ''}`} onClick={onClick} aria-pressed={active}><span>{label}</span><strong>{count}</strong></button>;
}
export function AuditPage({ query }: { query: URLSearchParams }) {
  const resource = useResource<Page<AuditItem>>(`/audit?page=${pageFromQuery(query)}`);
  return <>
    <PageHeading title="Audit history" description="Recorded imports, investigation proposals and reviewer decisions across the workspace." />
    <section className="sheet" aria-labelledby="audit-heading"><div className="section-bar"><h2 id="audit-heading">Activity register</h2><button className="button text-button" disabled={resource.loading} onClick={resource.refresh}>Refresh history</button></div>
      {resource.loading ? <Loading>Loading audit history…</Loading> : resource.error ? <ErrorBox message={resource.error} onRetry={resource.refresh} /> : resource.data && resource.data.items.length === 0 ? <Empty title="No recorded activity yet"><p>Imports and investigation decisions will appear here with the actor and time.</p></Empty> : resource.data && <><AuditTable items={resource.data.items} /><Pagination page={resource.data} label="events" onChange={(page) => { window.location.hash = `/audit?page=${page}`; }} /></>}
    </section>
  </>;
}
export function AuditTable({ items }: { items: AuditItem[] }) {
  return <div className="table-scroll" role="region" aria-label="Audit events" tabIndex={0}><table className="audit-table"><thead><tr><th scope="col">Time / actor</th><th scope="col">Action</th><th scope="col">Record</th><th scope="col">Note</th></tr></thead><tbody>{items.map((item) => <tr key={item.id}><td><time dateTime={item.createdAt}>{dateTime(item.createdAt)}</time><span className="row-meta">{item.actor.name} · {item.actor.role === 'OPERATOR' ? 'Operator' : 'Reviewer'}</span></td><td>{actionLabel(item.action)}</td><td><a className="row-link" href={item.caseId ? `#/runs/${item.runId}/cases/${item.caseId}` : `#/runs/${item.runId}`}>{item.caseId ? 'View case' : 'View run'}</a></td><td className="audit-note">{item.note || 'No note recorded.'}</td></tr>)}</tbody></table></div>;
}
function pageFromQuery(query: URLSearchParams) { const page = Number(query.get('page') || 0); return Number.isSafeInteger(page) && page >= 0 ? page : 0; }
