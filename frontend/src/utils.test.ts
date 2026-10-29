import { describe, expect, it } from 'vitest';
import type { CaseDetail, Proposal, User } from './types';
import { money, noteLength, readOnlyReason, validateImport, validateNote } from './utils';
import { ApiError, resultUncertain } from './api';

const operator: User = { id: 'operator', name: 'Demo Operator', email: 'operator@recondesk.test', role: 'OPERATOR' };
const reviewer: User = { id: 'reviewer', name: 'Demo Reviewer', email: 'reviewer@recondesk.test', role: 'REVIEWER' };
const proposal: Proposal = { id: 'proposal', caseId: 'case', note: 'A documented explanation.', createdBy: operator, createdAt: '2026-01-01T00:00:00Z', status: 'PENDING', decisionNote: null, decidedBy: null, decidedAt: null };
function detail(change: Partial<CaseDetail> = {}): CaseDetail {
  return { id: 'case', runId: 'run', runName: 'Sample run', reference: 'DEMO-1', classification: 'DIFFERENT', differences: ['AMOUNT'], state: 'OPEN', version: 0, internalCount: 1, partnerCount: 1, internalAmount: '1.00', partnerAmount: '2.00', delta: '-1.00', internalRows: [], partnerRows: [], proposals: [], audit: [], ...change };
}
describe('exact decimal presentation', () => {
  it('preserves large amounts, negative deltas and fractional digits without floating point conversion', () => {
    expect(money('999999999999.99')).toBe('999,999,999,999.99');
    expect(money('-1234567.01')).toBe('-1,234,567.01');
    expect(money('0.00')).toBe('0.00');
    expect(money(null)).toBe('Not comparable');
  });
});
describe('workflow eligibility', () => {
  it('validates trimmed Unicode code points rather than UTF-16 code units', () => {
    expect(noteLength('😀')).toBe(1);
    expect(noteLength(`  ${'😀'.repeat(10)}  `)).toBe(10);
    expect(validateNote('😀'.repeat(5))).toMatch(/10 and 2,000/);
    expect(validateNote('😀'.repeat(10))).toBeNull();
    expect(validateNote('😀'.repeat(2000))).toBeNull();
    expect(validateNote('😀'.repeat(2001))).toMatch(/10 and 2,000/);
  });
  it('lets an operator propose only an open case', () => {
    expect(readOnlyReason(detail(), operator)).toBeNull();
    expect(readOnlyReason(detail({ state: 'PENDING', proposals: [proposal] }), operator)).toMatch(/reviewer must decide/);
    expect(readOnlyReason(detail({ state: 'MATCHED', classification: 'MATCHED' }), operator)).toMatch(/does not need/);
    expect(readOnlyReason(detail({ state: 'RESOLVED' }), operator)).toMatch(/resolved/);
  });
  it('lets a different reviewer decide a pending proposal and explains missing or self review', () => {
    expect(readOnlyReason(detail(), reviewer)).toMatch(/operator must submit/);
    expect(readOnlyReason(detail({ state: 'PENDING', proposals: [proposal] }), reviewer)).toBeNull();
    expect(readOnlyReason(detail({ state: 'PENDING' }), reviewer)).toMatch(/could not be found/);
    expect(readOnlyReason(detail({ state: 'PENDING', proposals: [{ ...proposal, createdBy: reviewer }] }), reviewer)).toMatch(/cannot review your own/);
  });
  it('allows resubmission after rejection reopens the case', () => {
    expect(readOnlyReason(detail({ proposals: [{ ...proposal, status: 'REJECTED', decisionNote: 'Check the supporting evidence.' }] }), operator)).toBeNull();
  });
});
describe('import boundary validation', () => {
  const file = new File(['reference,date,amount,currency\nA,2026-01-01,1.00,IDR\n'], 'source.csv');
  it('requires both nonempty sources and a bounded name', () => {
    expect(validateImport('ab', file, file)).toMatch(/3 and 80/);
    expect(validateImport('a'.repeat(81), file, file)).toMatch(/3 and 80/);
    expect(validateImport('Sample run', null, file)).toMatch(/both/);
    expect(validateImport('Sample run', new File([], 'empty.csv'), file)).toMatch(/at least one/);
    expect(validateImport('Sample run', new File([new Uint8Array(1024 * 1024 + 1)], 'large.csv'), file)).toMatch(/1 MiB/);
    expect(validateImport('Sample run', file, file)).toBeNull();
    expect(validateImport('😀'.repeat(80), file, file)).toBeNull();
    expect(validateImport('😀'.repeat(81), file, file)).toMatch(/3 and 80/);
  });
  it('treats network and server errors as uncertain outcomes', () => {
    expect(resultUncertain(new ApiError(0, 'Network'))).toBe(true);
    expect(resultUncertain(new ApiError(500, 'Server'))).toBe(true);
    expect(resultUncertain(new Error('Unexpected failure'))).toBe(true);
    expect(resultUncertain(new ApiError(400, 'Invalid CSV'))).toBe(false);
    expect(resultUncertain(new ApiError(403, 'Forbidden'))).toBe(false);
  });
});
