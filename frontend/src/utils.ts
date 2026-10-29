import type { CaseDetail, User } from './types';

export const classificationLabels = {
  MATCHED: 'Matched', DIFFERENT: 'Different values', INTERNAL_ONLY: 'Internal only',
  PARTNER_ONLY: 'Partner only', DUPLICATE: 'Duplicate reference',
};
export const stateLabels = { MATCHED: 'Matched', OPEN: 'Open', PENDING: 'Awaiting review', RESOLVED: 'Resolved' };

export function money(value: string | null): string {
  if (value === null) return 'Not comparable';
  const [whole, fraction = '00'] = value.split('.');
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction}`;
}
export function dateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return value;
  return new Intl.DateTimeFormat('en-GB', {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'UTC',
  }).format(date) + ' UTC';
}
export function actionLabel(value: string): string {
  return value.replace(/_/g, ' ').toLowerCase().replace(/^./, (letter) => letter.toUpperCase());
}
export function noteLength(value: string): number { return Array.from(value.trim()).length; }
export function validateNote(value: string): string | null {
  const length = noteLength(value);
  return length < 10 || length > 2000 ? 'Write a note between 10 and 2,000 characters.' : null;
}
export function readOnlyReason(detail: CaseDetail, user: User): string | null {
  if (detail.state === 'MATCHED') return 'Both sources match. This case does not need an investigation proposal.';
  if (detail.state === 'RESOLVED') return 'A reviewer approved this investigation. This case is resolved.';
  if (user.role === 'REVIEWER' && detail.state === 'OPEN') return 'An operator must submit a proposal before you can review this case.';
  if (user.role === 'OPERATOR' && detail.state === 'PENDING') return 'A reviewer must decide the pending proposal. You can submit again if they reject it.';
  if (user.role === 'REVIEWER' && detail.state === 'PENDING') {
    const proposal = detail.proposals.find((item) => item.status === 'PENDING');
    if (!proposal) return 'The pending proposal could not be found. Refresh this case before reviewing.';
    if (proposal.createdBy.id === user.id) return 'A different person must review this proposal. You cannot review your own proposal.';
  }
  return null;
}
export function validateImport(name: string, internalFile: File | null, partnerFile: File | null): string | null {
  const nameLength = Array.from(name.trim()).length;
  if (nameLength < 3 || nameLength > 80) return 'Enter a run name between 3 and 80 characters.';
  if (!internalFile || !partnerFile) return 'Choose both an internal CSV and a partner CSV.';
  if (internalFile.size === 0 || partnerFile.size === 0) return 'Both CSV files must contain a header and at least one data row.';
  if (internalFile.size > 1024 * 1024 || partnerFile.size > 1024 * 1024) return 'Each CSV must be 1 MiB or smaller.';
  return null;
}
