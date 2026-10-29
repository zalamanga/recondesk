export interface User { id: string; name: string; email: string; role: 'OPERATOR' | 'REVIEWER' }
export interface Session { user: User; csrfToken: string }
export interface Page<T> { items: T[]; total: number; page: number; pageSize: number }
export interface Run {
  id: string; name: string; createdAt: string; createdBy: User;
  internalCount: number; partnerCount: number; caseCount: number;
  matchedCount: number; openCount: number; pendingCount: number; resolvedCount: number;
}
export type Classification = 'MATCHED' | 'DIFFERENT' | 'INTERNAL_ONLY' | 'PARTNER_ONLY' | 'DUPLICATE';
export type CaseState = 'MATCHED' | 'OPEN' | 'PENDING' | 'RESOLVED';
export interface CaseSummary {
  id: string; runId: string; reference: string; classification: Classification;
  differences: string[]; state: CaseState; version: number; internalCount: number; partnerCount: number;
  internalAmount: string | null; partnerAmount: string | null; delta: string | null;
}
export interface SourceRow { line: number; reference: string; date: string; amount: string; currency: string }
export interface Proposal {
  id: string; caseId: string; note: string; createdBy: User; createdAt: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED'; decisionNote: string | null;
  decidedBy: User | null; decidedAt: string | null;
}
export interface AuditItem {
  id: string; runId: string; caseId: string | null; actor: User;
  action: string; note: string; createdAt: string;
}
export interface CaseDetail extends CaseSummary {
  runName: string; internalRows: SourceRow[]; partnerRows: SourceRow[];
  proposals: Proposal[]; audit: AuditItem[];
}
