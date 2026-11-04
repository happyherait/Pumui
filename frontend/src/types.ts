export type Status = "PASS" | "WARN" | "REVIEW" | "FAIL";
export interface Check { id: string; label: string; status: Status; message: string }
export interface RequiredReview { role: string; reasons: string[] }
export interface MergeDecision { allowed: boolean; blockers: string[] }
export interface Content {
  title: string; category: string; itemCode: string; itemName: string;
  quantity: number; unit: string; unitPrice: number; supplierCode: string;
  projectCode: string; deliveryDate: string; soleSource: boolean;
  soleSourceReason: string; quoteText: string; notes: string;
  spec: Record<string, string>;
}
export interface User { id: string; name: string; dept: string; role: string }
export interface Revision { revNo: number; hash: string; authorId: string; createdAt: string; content: Content }
export interface Review {
  id: number; reviewerId: string; reviewerName: string; role: string; decision: string;
  comment: string; revisionHash: string; current: boolean; createdAt: string;
}
export interface EventRow { actorId: string; type: string; detail: string; createdAt: string }
export interface Summary { id: number; title: string; status: string; requesterName: string; amount: number; headRev: number; createdAt: string }
export interface Detail {
  id: number; title: string; status: string; requester: User; headRev: number; amount: number;
  headHash: string; revisions: Revision[]; checks: Check[]; requiredReviews: RequiredReview[];
  reviews: Review[]; merge: MergeDecision; events: EventRow[]; orderId: number | null;
}
export interface Sample { key: string; label: string; text: string; quote: string; draft: Content }
export interface Catalog {
  users: User[];
  projects: { code: string; name: string; available: number; endDate: string }[];
  suppliers: { code: string; name: string }[];
  items: { code: string; name: string; category: string; unit: string }[];
  samples: Sample[];
}
export interface Suggestion { field: string; value: string; reason: string }
export interface Suggestions { summary: string; changes: Suggestion[] }
export interface Api {
  catalog(): Promise<Catalog>;
  list(): Promise<Summary[]>;
  detail(id: number): Promise<Detail>;
  create(user: string, c: Content): Promise<Detail>;
  revise(id: number, user: string, c: Content): Promise<Detail>;
  review(id: number, user: string, input: { role: string; decision: string; comment: string }): Promise<Detail>;
  merge(id: number, user: string): Promise<Detail>;
  close(id: number, user: string): Promise<Detail>;
  draft(input: { text: string; quoteText: string }): Promise<Content>;
  suggest(id: number): Promise<Suggestions>;
}
