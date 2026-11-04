// 브라우저 데모용 규칙 엔진. backend/rules와 같은 골든 케이스로 검증한다.
import type { Check, MergeDecision, RequiredReview, Status } from "./types";

export interface Facts {
  category: string; itemCode?: string | null; quantity: number; unitPrice: number;
  spec: Record<string, string>; specCasNo?: string | null; masterCasNo?: string | null;
  regulatedHits?: string[]; strategicItem?: boolean; newSupplier?: boolean; soleSource?: boolean;
  soleSourceReason?: string | null; budgetAvailable: number; projectEndDate: string;
  deliveryDate?: string | null; stockOnHand?: number; lastOrderDaysAgo?: number | null;
  medianUnitPrice?: number | null;
}

const REQUIRED: Record<string, string[]> = {
  CHEMICAL: ["순도", "CAS", "포장단위"],
  EQUIPMENT: ["모델명", "정격전원", "설치장소"],
  CONSUMABLE: ["규격"],
  SERVICE: ["작업범위"],
};
const blank = (s?: string | null) => !s || s.trim() === "";
const won = (v: number) => `${v.toLocaleString("en-US")}원`;

export const amountOf = (f: Pick<Facts, "quantity" | "unitPrice">) => Math.round(f.quantity * f.unitPrice);
export const hazard = (f: Facts) => (f.regulatedHits ?? []).length > 0;
export const priceSpike = (f: Facts) => !!f.medianUnitPrice && f.unitPrice * 10 >= f.medianUnitPrice * 13;

export function runChecks(f: Facts): Check[] {
  const c = (id: string, label: string, status: Status, message: string): Check => ({ id, label, status, message });
  const out: Check[] = [];

  const required = REQUIRED[f.category];
  if (!required) out.push(c("spec.required", "사양 완전성", "FAIL", `알 수 없는 분류입니다: ${f.category}`));
  else {
    const missing = required.filter((k) => blank(f.spec[k]));
    out.push(missing.length
      ? c("spec.required", "사양 완전성", "FAIL", `필수 사양 누락: ${missing.join(", ")}`)
      : c("spec.required", "사양 완전성", "PASS", "필수 사양이 모두 있습니다."));
  }

  const amount = amountOf(f);
  out.push(amount > f.budgetAvailable
    ? c("budget.available", "과제 예산", "FAIL", `요청 ${won(amount)}이 가용 예산 ${won(f.budgetAvailable)}을 넘습니다.`)
    : c("budget.available", "과제 예산", "PASS", `가용 예산 ${won(f.budgetAvailable)} 안입니다.`));

  if (blank(f.deliveryDate)) out.push(c("budget.period", "과제 기간", "FAIL", "희망 납기가 없습니다."));
  else if (f.deliveryDate! > f.projectEndDate) out.push(c("budget.period", "과제 기간", "FAIL", `희망 납기가 과제 종료일 ${f.projectEndDate} 이후입니다.`));
  else out.push(c("budget.period", "과제 기간", "PASS", "과제 기간 안입니다."));

  if (!blank(f.masterCasNo) && f.masterCasNo !== f.specCasNo) out.push(c("hazard.cas-match", "CAS 대조", "FAIL", `사양의 CAS가 품목 마스터 ${f.masterCasNo}와 다릅니다.`));
  else if (f.category === "CHEMICAL" && blank(f.itemCode)) out.push(c("hazard.cas-match", "CAS 대조", "WARN", "품목 마스터에 없는 화학물질입니다."));
  else out.push(c("hazard.cas-match", "CAS 대조", "PASS", "품목 마스터와 일치하거나 대상이 아닙니다."));

  out.push(hazard(f)
    ? c("hazard.regulated", "규제 물질", "REVIEW", `규제 물질 포함: ${f.regulatedHits!.join(", ")}`)
    : c("hazard.regulated", "규제 물질", "PASS", "규제 물질 표에서 찾지 못했습니다."));

  out.push(f.strategicItem
    ? c("trade.strategic", "전략물자", "REVIEW", "전략물자 해당 품목입니다.")
    : c("trade.strategic", "전략물자", "PASS", "전략물자로 표시되지 않았습니다."));

  if (!f.soleSource) out.push(c("supplier.sole-source", "단독 구매", "PASS", "단독 구매가 아닙니다."));
  else if (blank(f.soleSourceReason)) out.push(c("supplier.sole-source", "단독 구매", "FAIL", "단독 구매 사유가 없습니다."));
  else out.push(c("supplier.sole-source", "단독 구매", "REVIEW", "단독 구매 사유를 구매 담당이 확인합니다."));

  out.push(f.newSupplier
    ? c("supplier.new", "공급사 등록", "REVIEW", "처음 거래하는 공급사입니다.")
    : c("supplier.new", "공급사 등록", "PASS", "등록된 공급사입니다."));

  const stock = f.stockOnHand ?? 0;
  if (stock >= f.quantity) out.push(c("duplicate.stock", "중복 구매", "WARN", `사내 재고로 충당할 수 있습니다: ${stock}`));
  else if (f.lastOrderDaysAgo != null && f.lastOrderDaysAgo <= 30) out.push(c("duplicate.stock", "중복 구매", "WARN", `${f.lastOrderDaysAgo}일 전에 같은 품목을 발주했습니다.`));
  else out.push(c("duplicate.stock", "중복 구매", "PASS", "최근 중복 구매가 없습니다."));

  const median = f.medianUnitPrice;
  if (!median) out.push(c("price.drift", "단가 변동", "PASS", "비교할 구매 이력이 없습니다."));
  else {
    const percent = Math.round(((f.unitPrice - median) * 100) / median);
    const text = `최근 1년 중앙값 ${won(median)} 대비 ${percent >= 0 ? "+" : ""}${percent}%입니다.`;
    out.push(c("price.drift", "단가 변동", priceSpike(f) ? "REVIEW" : f.unitPrice * 10 >= median * 11 ? "WARN" : "PASS", text));
  }
  return out;
}

export function resolveOwners(text: string, f: Facts): RequiredReview[] {
  const reasons = new Map<string, string[]>();
  const matches = (cond: string) => {
    if (cond.startsWith("amount>=")) return amountOf(f) >= Number(cond.slice(8));
    if (cond.startsWith("category=")) return cond.slice(9) === f.category;
    switch (cond) {
      case "always": return true;
      case "hazard": return hazard(f);
      case "strategic": return !!f.strategicItem;
      case "sole-source": return !!f.soleSource;
      case "new-supplier": return !!f.newSupplier;
      case "price-spike": return priceSpike(f);
      default: throw new Error(`알 수 없는 조건: ${cond}`);
    }
  };
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const tokens = line.split(/\s+/);
    const conditions = tokens.filter((t) => !t.startsWith("@"));
    const roles = tokens.filter((t) => t.startsWith("@")).map((t) => t.slice(1));
    if (conditions.every(matches)) {
      for (const role of roles) reasons.set(role, [...(reasons.get(role) ?? []), conditions.join(" ")]);
    }
  }
  return [...reasons].map(([role, list]) => ({ role, reasons: list }));
}

export function evaluateMerge(
  checks: Check[], required: RequiredReview[], requesterId: string, hash: string,
  reviews: { reviewerId: string; role: string; decision: string; revisionHash: string }[],
): MergeDecision {
  const blockers = checks.filter((c) => c.status === "FAIL").map((c) => `${c.label} 실패: ${c.message}`);
  const latest = new Map<string, (typeof reviews)[number]>();
  for (const r of reviews) if (r.revisionHash === hash && r.reviewerId !== requesterId) latest.set(r.role, r);
  const used = new Set<string>();
  for (const need of required) {
    const r = latest.get(need.role);
    if (!r) blockers.push(`${need.role} 리뷰 대기`);
    else if (r.decision === "CHANGES_REQUESTED") blockers.push(`${need.role} 변경 요청`);
    else if (used.has(r.reviewerId)) blockers.push(`${need.role} 리뷰어 중복: 한 사람은 한 역할만 맡을 수 있습니다`);
    else used.add(r.reviewerId);
  }
  return { allowed: blockers.length === 0, blockers };
}
