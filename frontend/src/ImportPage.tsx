import type { Dispatch, FormEvent, SetStateAction } from 'react';
import { useRef } from 'react';
import { ApiError, api, errorMessage, resultUncertain } from './api';
import { PageHeading } from './components';
import type { Run, User } from './types';
import { validateImport } from './utils';

export interface ImportDraft {
  name: string; internalFile: File | null; partnerFile: File | null;
  key: string | null; actorId: string | null;
  status: 'idle' | 'pending' | 'failed' | 'uncertain'; error: string | null;
}
export const newImportDraft = (): ImportDraft => ({
  name: '', internalFile: null, partnerFile: null, key: null, actorId: null, status: 'idle', error: null,
});

export default function ImportPage({ user, draft, setDraft, onImported }: {
  user: User; draft: ImportDraft; setDraft: Dispatch<SetStateAction<ImportDraft>>; onImported: (run: Run) => void;
}) {
  const requestInFlight = useRef(false);
  const locked = draft.status === 'pending' || draft.status === 'uncertain';
  const differentActor = draft.actorId !== null && draft.actorId !== user.id;
  const canImport = user.role === 'OPERATOR';
  function update(change: Partial<ImportDraft>) {
    if (locked) return;
    setDraft((current) => ({ ...current, ...change, key: null, actorId: null, error: null, status: 'idle' }));
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canImport || differentActor || draft.status === 'pending' || requestInFlight.current) return;
    const validation = validateImport(draft.name, draft.internalFile, draft.partnerFile);
    if (validation) { setDraft((current) => ({ ...current, error: validation })); return; }
    const key = draft.key ?? crypto.randomUUID();
    const body = new FormData();
    body.set('name', draft.name.trim()); body.set('internalFile', draft.internalFile!); body.set('partnerFile', draft.partnerFile!);
    requestInFlight.current = true;
    setDraft((current) => ({ ...current, key, actorId: user.id, status: 'pending', error: null }));
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 60_000);
    try {
      const run = await api<Run>('/runs', { method: 'POST', body, signal: controller.signal, headers: { 'Idempotency-Key': key } });
      setDraft(newImportDraft()); onImported(run);
    } catch (cause) {
      const uncertain = resultUncertain(cause) || (cause instanceof ApiError && cause.status === 409);
      setDraft((current) => ({ ...current, status: uncertain ? 'uncertain' : 'failed', error: controller.signal.aborted ? 'The server did not confirm the import within 60 seconds.' : errorMessage(cause) }));
    } finally { window.clearTimeout(timeout); requestInFlight.current = false; }
  }
  return <>
    <PageHeading title="Import CSV pair" description="Compare an internal ledger with a partner statement, grouped by transaction reference." />
    <div className="import-layout">
      <section className="sheet import-form-section" aria-labelledby="import-heading">
        <div className="section-title"><span className="section-number">01</span><h2 id="import-heading">Prepare a reconciliation run</h2></div>
        {!canImport && <div className="notice"><strong>Reviewer access</strong><p>Only an operator can import source files. You can read all existing runs and review pending proposals.</p></div>}
        {differentActor && <div className="notice error"><strong>This retry belongs to another account</strong><p>Sign in with the account that first submitted this pair to retry it safely.</p></div>}
        <form onSubmit={submit} className="stack-form">
          <div className="field"><label htmlFor="run-name">Run name</label><input id="run-name" value={draft.name} onChange={(event) => update({ name: event.target.value })} required disabled={locked || !canImport} placeholder="e.g. October settlement check" aria-describedby="run-name-help" /><p className="field-help" id="run-name-help">Use a clear name for this pair of source files. 3-80 characters.</p></div>
          <div className="upload-pair">
            <FileField side="Internal" file={draft.internalFile} disabled={locked || !canImport} onChange={(file) => update({ internalFile: file })} />
            <FileField side="Partner" file={draft.partnerFile} disabled={locked || !canImport} onChange={(file) => update({ partnerFile: file })} />
          </div>
          <div className="import-validation"><strong>Both sources are checked together.</strong><p>If either file is invalid, the server creates no run. Original source rows remain unchanged throughout investigation.</p></div>
          {draft.error && <div role="alert" className="notice error"><strong>{draft.status === 'uncertain' ? 'Import outcome is not confirmed' : 'Import could not be completed'}</strong><p>{draft.error}</p>{draft.status === 'uncertain' && <p>Keep this page open. The name, both files and request identity are retained. Retry sends this exact pair so a committed run is returned instead of duplicated.</p>}</div>}
          <div className="form-actions"><button className="button primary" disabled={!canImport || differentActor || draft.status === 'pending'}>{draft.status === 'pending' ? 'Validating and comparing…' : draft.status === 'uncertain' ? 'Retry the same CSV pair' : 'Import and compare'}</button><span className="muted">Up to 1,000 rows per source</span></div>
          {draft.status === 'pending' && <p role="status" className="field-help">Waiting for the server to confirm the complete run. You can leave this view and return while it finishes.</p>}
          {draft.key && <details className="request-details"><summary>Request identity</summary><code>{draft.key}</code><p className="field-help">This identity stays the same when you retry these exact files.</p></details>}
        </form>
      </section>
      <aside className="import-guide">
        <div className="section-title"><span className="section-number">02</span><h2>CSV requirements</h2></div>
        <p>Use UTF-8 CSV with this exact header:</p><code className="csv-header">reference,date,amount,currency</code>
        <dl className="requirements"><div><dt>Reference</dt><dd>1-64 letters, numbers, dots, underscores, slashes or hyphens.</dd></div><div><dt>Date</dt><dd>Valid date in YYYY-MM-DD format, with a year from 0001 to 9999.</dd></div><div><dt>Amount</dt><dd>Nonnegative decimal, up to 999999999999.99 and 2 decimal places.</dd></div><div><dt>Currency</dt><dd>IDR or USD.</dd></div><div><dt>File limits</dt><dd>1 MiB and 1,000 data rows per file. At least one row; no blank records.</dd></div></dl>
        <div className="sample-downloads"><h3>Try the synthetic samples</h3><p>Download the pair to inspect matching, different, missing and duplicate references.</p><a href="/samples/internal.csv" download className="sample-link">Download internal.csv <span>CSV</span></a><a href="/samples/partner.csv" download className="sample-link">Download partner.csv <span>CSV</span></a></div>
      </aside>
    </div>
  </>;
}
function FileField({ side, file, disabled, onChange }: { side: string; file: File | null; disabled: boolean; onChange: (file: File | null) => void }) {
  const id = `${side.toLowerCase()}-file`;
  return <div className="file-field"><div className="source-caption"><span className="source-mark" aria-hidden="true">{side === 'Internal' ? 'I' : 'P'}</span><label htmlFor={id}>{side} source</label></div><p>{side === 'Internal' ? 'Your transaction ledger' : 'The partner statement'}</p><input id={id} type="file" accept=".csv,text/csv" disabled={disabled} required={!file} onChange={(event) => onChange(event.target.files?.[0] ?? null)} aria-describedby={`${id}-help`} />{file && <div className="file-selected">{file.name}<span>{(file.size / 1024).toFixed(1)} KiB</span></div>}<p className="field-help" id={`${id}-help`}>CSV only, maximum 1 MiB</p></div>;
}
