// 서버 없이 도는 공개 데모. 규칙은 진짜이고, AI 결과만 미리 준비한 값이다.
import owners from "../../shared/PROCUREMENTOWNERS?raw";
import seedJson from "../../shared/seed.json";
import { amountOf, evaluateMerge, resolveOwners, runChecks, type Facts } from "./engine";
import type { Api, Catalog, Check, Content, Detail, EventRow, RequiredReview, Review, Revision, Sample } from "./types";

const seed = seedJson as unknown as {
  users: Catalog["users"];
  projects: { code: string; name: string; ownerDept: string; budgetTotal: number; budgetUsed: number; endDate: string }[];
  suppliers: { code: string; name: string; approved: boolean }[];
  items: { code: string; name: string; category: string; unit: string; casNo: string | null; strategic: boolean }[];
  regulated: { casNo: string; name: string }[];
  inventory: { itemCode: string; quantity: number }[];
  orders: { itemCode: string; unitPrice: number; daysAgo: number }[];
  samples: Sample[];
};

interface Pr { id: number; requesterId: string; status: string; revisions: Revision[]; reviews: Review[]; events: EventRow[]; orderId: number | null; snapshot?: { checks: Check[]; required: RequiredReview[] } }
interface State { nextId: number; nextReview: number; used: Record<string, number>; orders: { itemCode: string; unitPrice: number; daysAgo: number }[]; prs: Pr[] }

const KEY = "pumui-demo-v1";
const now = () => {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16).replace("T", " ");
};
const CAS = /(?<![\d-])\d{2,7}-\d{2}-\d(?![\d-])/g;

async function sha256(text: string) {
  const data = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(data)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function fresh(): State {
  return { nextId: 1, nextReview: 1, used: Object.fromEntries(seed.projects.map((p) => [p.code, p.budgetUsed])), orders: [...seed.orders], prs: [] };
}

let state: State = load();
let ready = state.prs.length > 0 ? Promise.resolve() : seedRequests();

function load(): State {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return JSON.parse(raw);
  } catch { /* 저장소를 못 쓰면 메모리로만 동작한다 */ }
  return fresh();
}
function save() {
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* 위와 같음 */ }
}
async function seedRequests() {
  await open("kim", seed.samples.find((s) => s.key === "normal")!.draft);
}
export async function resetDemo() {
  state = fresh();
  await seedRequests();
}

function factsOf(c: Content): Facts {
  const project = seed.projects.find((p) => p.code === c.projectCode);
  if (!project) throw new Error("과제 코드를 찾을 수 없습니다.");
  const item = seed.items.find((i) => i.code === c.itemCode);
  if (c.itemCode && !item) throw new Error("품목 코드를 찾을 수 없습니다.");
  const text = [c.title, c.itemName, c.notes, c.quoteText, ...Object.values(c.spec)].join("\n");
  const found = new Set(text.match(CAS) ?? []);
  if (item?.casNo) found.add(item.casNo);
  const hist = state.orders.filter((o) => o.itemCode === c.itemCode && o.daysAgo <= 365);
  const prices = hist.map((o) => o.unitPrice).sort((a, b) => a - b);
  const mid = prices.length / 2;
  const median = prices.length ? Math.round(prices.length % 2 ? prices[Math.floor(mid)] : (prices[mid - 1] + prices[mid]) / 2) : null;
  return {
    category: c.category, itemCode: c.itemCode, quantity: c.quantity, unitPrice: c.unitPrice, spec: c.spec,
    specCasNo: c.spec["CAS"], masterCasNo: item?.casNo,
    regulatedHits: seed.regulated.filter((r) => found.has(r.casNo)).map((r) => `${r.casNo} ${r.name}`),
    strategicItem: item?.strategic, newSupplier: !seed.suppliers.find((s) => s.code === c.supplierCode)?.approved,
    soleSource: c.soleSource, soleSourceReason: c.soleSourceReason,
    budgetAvailable: project.budgetTotal - state.used[project.code], projectEndDate: project.endDate, deliveryDate: c.deliveryDate,
    stockOnHand: seed.inventory.filter((i) => i.itemCode === c.itemCode).reduce((n, i) => n + i.quantity, 0),
    lastOrderDaysAgo: hist.length ? Math.min(...hist.map((o) => o.daysAgo)) : null, medianUnitPrice: median,
  };
}

function view(pr: Pr): Detail {
  const head = pr.revisions[pr.revisions.length - 1];
  const f = factsOf(head.content);
  const checks = pr.snapshot?.checks ?? runChecks(f);
  const requiredReviews = pr.snapshot?.required ?? resolveOwners(owners, f);
  return {
    id: pr.id, title: head.content.title, status: pr.status,
    requester: seed.users.find((u) => u.id === pr.requesterId)!, headRev: head.revNo, amount: amountOf(f), headHash: head.hash,
    revisions: pr.revisions, checks, requiredReviews,
    reviews: pr.reviews.map((r) => ({ ...r, current: r.revisionHash === head.hash })),
    merge: pr.snapshot ? { allowed: true, blockers: [] } : evaluateMerge(checks, requiredReviews, pr.requesterId, head.hash, pr.reviews),
    events: pr.events, orderId: pr.orderId,
  };
}

const get = (id: number) => {
  const pr = state.prs.find((p) => p.id === id);
  if (!pr) throw new Error("구매요청을 찾을 수 없습니다.");
  return pr;
};
const open_ = (pr: Pr) => { if (pr.status !== "OPEN") throw new Error("이미 끝난 구매요청입니다."); };
const note = (pr: Pr, actorId: string, type: string, detail: string) => pr.events.push({ actorId, type, detail, createdAt: now() });

async function revision(revNo: number, c: Content, authorId: string): Promise<Revision> {
  return { revNo, hash: await sha256(JSON.stringify(c)), authorId, createdAt: now(), content: c };
}
async function open(user: string, c: Content) {
  factsOf(c);
  const pr: Pr = { id: state.nextId++, requesterId: user, status: "OPEN", revisions: [await revision(1, c, user)], reviews: [], events: [], orderId: null };
  note(pr, user, "OPENED", "구매요청을 열었습니다.");
  state.prs.push(pr);
  save();
  return view(pr);
}

export const demoApi: Api = {
  async catalog() {
    await ready;
    return {
      users: seed.users, suppliers: seed.suppliers, items: seed.items, samples: seed.samples,
      projects: seed.projects.map((p) => ({ code: p.code, name: p.name, available: p.budgetTotal - state.used[p.code], endDate: p.endDate })),
    };
  },
  async list() {
    await ready;
    return [...state.prs].reverse().map((p) => {
      const d = view(p);
      return { id: d.id, title: d.title, status: d.status, requesterName: d.requester.name, amount: d.amount, headRev: d.headRev, createdAt: p.revisions[0].createdAt };
    });
  },
  async detail(id) { await ready; return view(get(id)); },
  create: (user, c) => open(user, c),
  async revise(id, user, c) {
    const pr = get(id);
    open_(pr);
    if (pr.requesterId !== user) throw new Error("요청자만 사양을 고칠 수 있습니다.");
    factsOf(c);
    const rev = await revision(pr.revisions.length + 1, c, user);
    pr.revisions.push(rev);
    note(pr, user, "REVISED", `리비전 ${rev.revNo}을 올렸습니다. 이전 승인은 무효가 됩니다.`);
    save();
    return view(pr);
  },
  async review(id, user, input) {
    const pr = get(id);
    open_(pr);
    const me = seed.users.find((u) => u.id === user)!;
    if (user === pr.requesterId) throw new Error("본인 요청은 리뷰할 수 없습니다.");
    if (me.role !== input.role) throw new Error("본인 역할로만 리뷰할 수 있습니다.");
    const d = view(pr);
    if (!d.requiredReviews.some((r) => r.role === input.role)) throw new Error("이 요청에 필요한 리뷰 역할이 아닙니다.");
    pr.reviews.push({ id: state.nextReview++, reviewerId: user, reviewerName: me.name, role: input.role, decision: input.decision, comment: input.comment, revisionHash: d.headHash, current: true, createdAt: now() });
    note(pr, user, input.decision, `${input.role} 리뷰를 남겼습니다.`);
    save();
    return view(pr);
  },
  async merge(id, user) {
    const pr = get(id);
    open_(pr);
    const me = seed.users.find((u) => u.id === user)!;
    if (user !== pr.requesterId && me.role !== "PROCUREMENT") throw new Error("요청자 또는 구매 담당만 발주로 전환할 수 있습니다.");
    const d = view(pr);
    if (!d.merge.allowed) {
      note(pr, user, "MERGE_REJECTED", d.merge.blockers.join("; "));
      save();
      throw new Error(d.merge.blockers.join("; "));
    }
    const c = pr.revisions[pr.revisions.length - 1].content;
    pr.snapshot = { checks: d.checks, required: d.requiredReviews };
    state.used[c.projectCode] += d.amount;
    if (c.itemCode) state.orders.push({ itemCode: c.itemCode, unitPrice: c.unitPrice, daysAgo: 0 });
    pr.status = "MERGED";
    pr.orderId = pr.id + 1000;
    note(pr, user, "MERGED", "발주로 전환했습니다.");
    save();
    return view(pr);
  },
  async close(id, user) {
    const pr = get(id);
    open_(pr);
    if (pr.requesterId !== user) throw new Error("요청자만 닫을 수 있습니다.");
    pr.status = "CLOSED";
    note(pr, user, "CLOSED", "구매요청을 닫았습니다.");
    save();
    return view(pr);
  },
  async draft({ text, quoteText }) {
    const sample = seed.samples.find((s) => s.text === text.trim());
    if (sample) return sample.draft;
    return {
      title: text.trim().slice(0, 40) || "새 구매요청", category: "CONSUMABLE", itemCode: "", itemName: "", quantity: 1, unit: "EA", unitPrice: 0,
      supplierCode: "SUP-01", projectCode: "RD-2026-014", deliveryDate: "", soleSource: false, soleSourceReason: "", quoteText, notes: "", spec: { 규격: "" },
    };
  },
  async suggest(id) {
    const d = view(get(id));
    const head = d.revisions[d.revisions.length - 1].content;
    const project = seed.projects.find((p) => p.code === head.projectCode)!;
    const changes = [];
    if (d.checks.find((c) => c.id === "budget.period")?.status === "FAIL") changes.push({ field: "deliveryDate", value: project.endDate, reason: "과제 종료일 안으로 납기를 맞춥니다." });
    if (d.checks.find((c) => c.id === "supplier.sole-source")?.status === "FAIL") changes.push({ field: "soleSourceReason", value: "해당 사양을 공급할 수 있는 유일한 등록 공급사입니다.", reason: "사유를 확인해 고쳐 주세요." });
    return { summary: changes.length ? "데모 규칙이 만든 수정 제안입니다." : "데모에서 제안할 수정이 없습니다.", changes };
  },
};
