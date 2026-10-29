import { useRef, useState } from 'react';
import type { FormEvent, KeyboardEvent } from 'react';
import { ApiError, api, errorMessage, resultUncertain } from './api';
import { Empty, ErrorBox, Loading, PageHeading, StateLabel, useResource } from './components';
import type { CaseDetail, Proposal, SourceRow, User } from './types';
import { actionLabel, classificationLabels, dateTime, money, noteLength, readOnlyReason, validateNote } from './utils';
import { AuditTable } from './WorkspacePages';

export default function CasePage({ runId, caseId, query, user }: { runId: string; caseId: string; query: URLSearchParams; user: User }) {
  const resource = useResource<CaseDetail>(`/cases/${encodeURIComponent(caseId)}`);
  const [tab, setTab] = useState<'sources' | 'history'>('sources');
  const [note, setNote] = useState('');
  const [decision, setDecision] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [busy, setBusy] = useState(false);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const requestInFlight = useRef(false);
  function refresh() { resource.refresh(); setNeedsRefresh(false); setMutationError(null); setSuccess(null); }
  function changeTab(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : 1 - index;
      setTab(next === 0 ? 'sources' : 'history'); tabs.current[next]?.focus();
    }
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!resource.data || busy || requestInFlight.current || needsRefresh || readOnlyReason(resource.data, user)) return;
    const text = note.trim();
    const validation = validateNote(text);
    if (validation) { setMutationError(validation); return; }
    const detail = resource.data;
    const pending = detail.proposals.find((proposal) => proposal.status === 'PENDING');
    const reviewing = user.role === 'REVIEWER';
    if (reviewing && !pending) { setMutationError('No pending proposal is available. Refresh this case.'); setNeedsRefresh(true); return; }
    requestInFlight.current = true;
    setBusy(true); setMutationError(null); setSuccess(null);
    try {
      const updated = await api<CaseDetail>(reviewing ? `/proposals/${pending!.id}/decision` : `/cases/${caseId}/proposals`, {
        method: 'POST', body: JSON.stringify({ expectedVersion: detail.version, note: text, ...(reviewing ? { decision } : {}) }),
      });
      resource.setData(updated); setNote('');
      setSuccess(reviewing ? decision === 'APPROVE' ? 'Proposal approved. The case is resolved.' : 'Proposal rejected. The case is open for a new proposal.' : 'Proposal submitted. A different reviewer can now decide it.');
    } catch (cause) {
      const stale = cause instanceof ApiError && cause.status === 409;
      const uncertain = resultUncertain(cause);
      setNeedsRefresh(stale || uncertain);
      setMutationError(stale ? 'This case changed while you were working. Refresh it to read the current proposal and status before continuing.' : uncertain ? 'The result is not confirmed. Refresh this case to check whether the server recorded your action before submitting again. Your note is retained.' : errorMessage(cause));
    } finally { requestInFlight.current = false; setBusy(false); }
  }
  if (resource.loading) return <Loading>Loading the complete case…</Loading>;
  if (resource.error || !resource.data) return <ErrorBox message={resource.error || 'The case could not be found.'} onRetry={refresh} />;
  const detail = resource.data;
  const reason = readOnlyReason(detail, user);
  const pending = detail.proposals.find((proposal) => proposal.status === 'PENDING');
  return <>
    <a className="back-link" href={`#/runs/${runId}${query.size ? `?${query}` : ''}`}>Back to {detail.runName}</a>
    <PageHeading title={detail.reference} description={<><span>{classificationLabels[detail.classification]}{detail.differences.length ? ` · ${detail.differences.map(actionLabel).join(', ')}` : ''}</span><span className="case-run-name">{detail.runName}</span></>} action={<StateLabel state={detail.state} />} />
    {success && <div className="notice success" role="status">{success}</div>}
    <div className="case-layout">
      <section className="sheet case-evidence" aria-label="Case evidence">
        <div className="tabs" role="tablist" aria-label="Case details">
          <button id="tab-sources" type="button" role="tab" aria-selected={tab === 'sources'} aria-controls="panel-sources" tabIndex={tab === 'sources' ? 0 : -1} ref={(node) => { tabs.current[0] = node; }} onClick={() => setTab('sources')} onKeyDown={(event) => changeTab(event, 0)}>Source comparison</button>
          <button id="tab-history" type="button" role="tab" aria-selected={tab === 'history'} aria-controls="panel-history" tabIndex={tab === 'history' ? 0 : -1} ref={(node) => { tabs.current[1] = node; }} onClick={() => setTab('history')} onKeyDown={(event) => changeTab(event, 1)}>History <span>{detail.proposals.length}</span></button>
        </div>
        {tab === 'sources' ? <div id="panel-sources" role="tabpanel" aria-labelledby="tab-sources" tabIndex={0}>
          <div className="comparison-intro"><h2>Original source rows</h2><p>{comparisonExplanation(detail)}</p></div>
          <div className="source-comparison"><SourceBlock label="Internal" rows={detail.internalRows} differences={detail.differences} /><SourceBlock label="Partner" rows={detail.partnerRows} differences={detail.differences} /></div>
          <div className="comparison-footer"><span>Delta</span><strong className="amount">{money(detail.delta)}</strong><p>{detail.delta === null ? detail.classification === 'DUPLICATE' ? 'Duplicate source rows do not have a comparable delta.' : detail.differences.includes('CURRENCY') ? 'Different source currencies do not have a comparable monetary delta.' : 'No comparable monetary delta is available.' : detail.internalCount === 0 || detail.partnerCount === 0 ? 'The missing source contributes zero. The delta is internal amount minus partner amount.' : 'Internal amount minus partner amount, in the source currency.'}</p></div>
        </div> : <div id="panel-history" role="tabpanel" aria-labelledby="tab-history" tabIndex={0}>
          <div className="comparison-intro"><h2>Proposal history</h2><p>Every explanation and review decision for this reference.</p></div>
          {detail.proposals.length === 0 ? <Empty title="No investigation proposals"><p>{detail.state === 'MATCHED' ? 'Both sources match, so no investigation is needed.' : 'An operator can submit an explanation from the investigation panel.'}</p></Empty> : <ol className="proposal-history">{detail.proposals.map((proposal) => <li key={proposal.id}><ProposalRecord proposal={proposal} /></li>)}</ol>}
          <div className="section-bar audit-subheading"><h2>Case audit events</h2></div>{detail.audit.length ? <AuditTable items={detail.audit} /> : <p className="inline-empty">No case-specific audit events were returned.</p>}
        </div>}
      </section>
      <aside className="investigation-panel sheet" aria-labelledby="investigation-heading">
        <div className="section-bar"><h2 id="investigation-heading">Investigation</h2><span className="version-label">Version {detail.version}</span></div>
        <div className="investigation-body">
          {pending && <div className="pending-proposal"><h3>Pending explanation</h3><p className="preserve-text">{pending.note}</p><p className="field-help">{pending.createdBy.name}<br />{dateTime(pending.createdAt)}</p></div>}
          {reason ? <div className="read-only-message"><h3>{detail.state === 'RESOLVED' ? 'Investigation resolved' : detail.state === 'MATCHED' ? 'No action required' : 'Read-only at this stage'}</h3><p>{reason}</p>{detail.state === 'RESOLVED' && <button className="button secondary" onClick={() => { setTab('history'); tabs.current[1]?.focus(); }}>Read the decision</button>}</div> : <form className="stack-form" onSubmit={submit}>
            <div><h3>{user.role === 'OPERATOR' ? detail.proposals.some((item) => item.status === 'REJECTED') ? 'Submit a revised explanation' : 'Propose an explanation' : 'Review this proposal'}</h3><p className="muted">{user.role === 'OPERATOR' ? 'Explain the discrepancy and the evidence for your conclusion. A different reviewer must decide it.' : 'Approve the explanation or reject it with a note so the operator can investigate again.'}</p></div>
            {user.role === 'REVIEWER' && <fieldset className="decision-options" disabled={busy || needsRefresh}><legend>Decision</legend><label><input type="radio" name="decision" value="APPROVE" checked={decision === 'APPROVE'} onChange={() => setDecision('APPROVE')} /><span>Approve <small>Resolve the investigation</small></span></label><label><input type="radio" name="decision" value="REJECT" checked={decision === 'REJECT'} onChange={() => setDecision('REJECT')} /><span>Reject <small>Return to the operator</small></span></label></fieldset>}
            <div className="field"><label htmlFor="investigation-note">{user.role === 'OPERATOR' ? 'Investigation note' : 'Decision note'}</label><textarea id="investigation-note" rows={6} required value={note} disabled={busy || needsRefresh} onChange={(event) => setNote(event.target.value)} aria-describedby="note-help" /><p id="note-help" className="field-help">10-2,000 characters, excluding outer spaces. <span className="character-count">{noteLength(note)}/2,000</span></p></div>
            {mutationError && <div className="notice error" role="alert"><p>{mutationError}</p>{needsRefresh && <button type="button" className="button secondary" onClick={refresh}>Refresh case</button>}</div>}
            <button className={`button ${decision === 'REJECT' && user.role === 'REVIEWER' ? 'reject-button' : 'primary'}`} disabled={busy || needsRefresh}>{busy ? user.role === 'OPERATOR' ? 'Submitting proposal…' : 'Saving decision…' : user.role === 'OPERATOR' ? 'Submit for review' : decision === 'APPROVE' ? 'Approve proposal' : 'Reject proposal'}</button>
            <p className="field-help">{user.role === 'OPERATOR' ? 'Submitting locks this proposal until a reviewer decides.' : 'This records an investigation decision. Source transactions remain unchanged.'}</p>
          </form>}
          <button className="button text-button refresh-case" disabled={busy} onClick={refresh}>Refresh case status</button>
        </div>
      </aside>
    </div>
  </>;
}
function comparisonExplanation(detail: CaseDetail): string {
  if (detail.classification === 'DUPLICATE') return 'A reference appears more than once in at least one source. All rows are retained; no arbitrary row is selected for comparison.';
  if (detail.classification === 'MATCHED') return 'The reference, date, amount and currency agree across both sources.';
  if (detail.classification === 'INTERNAL_ONLY') return 'This reference exists in the internal source and has no partner row.';
  if (detail.classification === 'PARTNER_ONLY') return 'This reference exists in the partner source and has no internal row.';
  return `The two sources disagree on ${detail.differences.map((value) => value.toLowerCase()).join(', ')}. The differing fields are marked below.`;
}
function SourceBlock({ label, rows, differences }: { label: string; rows: SourceRow[]; differences: string[] }) {
  const different = (field: string) => differences.includes(field) ? 'different-field' : '';
  return <section className="source-block" aria-labelledby={`source-${label}`}><div className="source-block-heading"><div className="source-caption"><span className="source-mark" aria-hidden="true">{label === 'Internal' ? 'I' : 'P'}</span><h3 id={`source-${label}`}>{label} source</h3></div><span>{rows.length} {rows.length === 1 ? 'row' : 'rows'}</span></div>{rows.length === 0 ? <div className="missing-source"><strong>No source row</strong><p>This reference does not appear in the {label.toLowerCase()} file.</p></div> : <div className="source-rows">{rows.map((row) => <div className="source-row" key={row.line}><div className="source-line">CSV record {row.line}</div><dl><div><dt>Reference</dt><dd className="reference">{row.reference}</dd></div><div className={different('DATE')}><dt>Date {differences.includes('DATE') && <span className="sr-only">differs</span>}</dt><dd>{row.date}</dd></div><div className={different('AMOUNT')}><dt>Amount {differences.includes('AMOUNT') && <span className="sr-only">differs</span>}</dt><dd className="amount">{money(row.amount)}</dd></div><div className={different('CURRENCY')}><dt>Currency {differences.includes('CURRENCY') && <span className="sr-only">differs</span>}</dt><dd>{row.currency}</dd></div></dl></div>)}</div>}</section>;
}
function ProposalRecord({ proposal }: { proposal: Proposal }) {
  return <article className="proposal-record"><div className="proposal-record-title"><h3>{proposal.status === 'PENDING' ? 'Awaiting review' : proposal.status === 'APPROVED' ? 'Approved explanation' : 'Rejected explanation'}</h3><time dateTime={proposal.createdAt}>{dateTime(proposal.createdAt)}</time></div><p className="field-help">Submitted by {proposal.createdBy.name}</p><p className="preserve-text">{proposal.note}</p>{proposal.decisionNote && <div className="decision-record"><strong>{proposal.status === 'APPROVED' ? 'Approval note' : 'Rejection note'}</strong><p className="preserve-text">{proposal.decisionNote}</p><p className="field-help">{proposal.decidedBy?.name}{proposal.decidedAt ? ` · ${dateTime(proposal.decidedAt)}` : ''}</p></div>}</article>;
}
