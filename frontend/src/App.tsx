import { useCallback, useEffect, useState, type FormEvent } from "react";
import { demoApi, resetDemo } from "./demoApi";
import { httpApi } from "./httpApi";
import type { Api, Catalog, Content, Detail, Review, Revision, Suggestions, Summary, User } from "./types";

const DEMO = import.meta.env.VITE_DEMO === "1";
const api: Api = DEMO ? demoApi : httpApi;

const ROLE: Record<string, string> = {
  RESEARCHER: "연구원", TEAM_LEAD: "팀장", PROCUREMENT: "구매 담당", EHS: "안전환경",
  TRADE_COMPLIANCE: "수출입 관리", FACILITY: "연구설비", RND_DIRECTOR: "연구소장",
};
const CATEGORY: Record<string, string> = { CHEMICAL: "화학소재", EQUIPMENT: "장비", CONSUMABLE: "소모품", SERVICE: "용역" };
const STATUS: Record<string, string> = { OPEN: "진행 중", MERGED: "발주 전환", CLOSED: "닫힘" };
const CHECK: Record<string, string> = { PASS: "통과", WARN: "참고", REVIEW: "리뷰 필요", FAIL: "실패" };
const EVENT: Record<string, string> = { OPENED: "요청", REVISED: "리비전", APPROVED: "승인", CHANGES_REQUESTED: "변경 요청", MERGE_REJECTED: "머지 거부", MERGED: "발주", CLOSED: "닫음" };
const FIELD: Record<string, string> = {
  title: "제목", category: "분류", itemCode: "품목 코드", itemName: "품명", quantity: "수량", unit: "단위", unitPrice: "단가",
  supplierCode: "공급사", projectCode: "과제", deliveryDate: "희망 납기", soleSource: "단독 구매", soleSourceReason: "단독 구매 사유", quoteText: "견적서", notes: "메모",
};
const won = (v: number) => `${v.toLocaleString("ko-KR")}원`;
const humanize = (s: string) => Object.entries(ROLE).reduce((t, [k, v]) => t.replaceAll(k, v), s);

function flatten(c: Content) {
  const { spec, ...rest } = c;
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(rest)) out[FIELD[k] ?? k] = String(v ?? "");
  for (const [k, v] of Object.entries(spec)) out[`사양: ${k}`] = v;
  return out;
}
function diff(a: Content, b: Content) {
  const x = flatten(a), y = flatten(b);
  return [...new Set([...Object.keys(x), ...Object.keys(y)])]
    .filter((k) => (x[k] ?? "") !== (y[k] ?? ""))
    .map((k) => ({ key: k, before: x[k] ?? "", after: y[k] ?? "" }));
}

function route() {
  const m = location.hash.match(/^#\/r\/(\d+)/);
  return m ? Number(m[1]) : location.hash === "#/new" ? "new" as const : null;
}

export function App() {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [list, setList] = useState<Summary[]>([]);
  const [userId, setUserId] = useState("kim");
  const [view, setView] = useState(route());
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    const [c, l] = await Promise.all([api.catalog(), api.list()]);
    setCatalog(c);
    setList(l);
  }, []);
  useEffect(() => { refresh().catch((e) => setError(e.message)); }, [refresh]);
  useEffect(() => {
    const on = () => { setView(route()); setError(""); };
    addEventListener("hashchange", on);
    return () => removeEventListener("hashchange", on);
  }, []);

  if (!catalog) return <main className="boot">{error || "불러오는 중입니다."}</main>;
  const user = catalog.users.find((u) => u.id === userId)!;
  const active = view ?? list[0]?.id ?? "new";

  return (
    <div className="shell">
      <header className="top">
        <a className="brand" href="#/">품의</a>
        <p>구매요청을 Pull Request처럼</p>
        <div className="top-actions">
          {DEMO && <button className="ghost" onClick={async () => { await resetDemo(); location.hash = "#/"; await refresh(); }}>데모 초기화</button>}
          <label>접속 사용자
            <select value={userId} onChange={(e) => setUserId(e.target.value)}>
              {catalog.users.map((u) => <option key={u.id} value={u.id}>{u.name} ({ROLE[u.role]})</option>)}
            </select>
          </label>
        </div>
      </header>
      {DEMO && <p className="demo-note">공개 데모입니다. 규칙 판단은 실제 코드와 같고, AI 결과는 미리 준비한 값이며, 회사와 데이터는 모두 가상입니다.</p>}
      <div className="layout">
        <nav className="ledger" aria-label="구매요청 목록">
          <a className="new" href="#/new">새 구매요청</a>
          <ol>
            {list.map((p) => (
              <li key={p.id}>
                <a href={`#/r/${p.id}`} aria-current={active === p.id ? "page" : undefined}>
                  <span className="no">#{p.id}</span>
                  <strong>{p.title}</strong>
                  <span>{won(p.amount)}</span>
                  <span className={`state ${p.status}`}>{STATUS[p.status]}</span>
                </a>
              </li>
            ))}
          </ol>
        </nav>
        <main className="desk">
          {error && <p className="error" role="alert">{humanize(error)}</p>}
          {active === "new"
            ? <Composer catalog={catalog} user={user} onError={setError} onDone={async (id) => { await refresh(); location.hash = `#/r/${id}`; }} />
            : <Sheet key={active} id={active} user={user} catalog={catalog} onError={setError} onChange={refresh} />}
        </main>
      </div>
    </div>
  );
}

function Composer({ catalog, user, onDone, onError }: { catalog: Catalog; user: User; onDone: (id: number) => void; onError: (e: string) => void }) {
  const [text, setText] = useState("");
  const [quote, setQuote] = useState("");
  const [draft, setDraft] = useState<Content | null>(null);
  const [busy, setBusy] = useState(false);

  const makeDraft = async () => {
    setBusy(true);
    onError("");
    try { setDraft(await api.draft({ text, quoteText: quote })); }
    catch (e) { onError((e as Error).message); }
    finally { setBusy(false); }
  };

  return (
    <section className="sheet">
      <h1>새 구매요청</h1>
      <p className="lead">필요한 것을 평소 말로 적으면 AI가 양식 초안을 만듭니다. 내용을 확인한 뒤 요청을 여세요.</p>
      <div className="samples">
        {catalog.samples.map((s) => <button key={s.key} className="ghost" onClick={() => { setText(s.text); setQuote(s.quote); setDraft(null); }}>{s.label}</button>)}
      </div>
      <label className="field">요청 내용<textarea rows={3} value={text} onChange={(e) => setText(e.target.value)} placeholder="예: 수명 평가용 NMC811 양극재 5kg, 순도 99.5%" /></label>
      <label className="field">견적서 본문 (선택)<textarea rows={5} value={quote} onChange={(e) => setQuote(e.target.value)} placeholder="공급사 견적서 내용을 붙여 넣으세요" /></label>
      <button className="primary" disabled={busy || !text.trim()} onClick={makeDraft}>{busy ? "초안을 만드는 중입니다" : "AI 초안 만들기"}</button>
      {draft && (
        <ContentForm catalog={catalog} initial={draft} submit="구매요청 열기" onSubmit={async (c) => {
          try { const d = await api.create(user.id, c); onDone(d.id); }
          catch (e) { onError((e as Error).message); }
        }} />
      )}
    </section>
  );
}

function ContentForm({ catalog, initial, submit, onSubmit }: { catalog: Catalog; initial: Content; submit: string; onSubmit: (c: Content) => void }) {
  const [c, setC] = useState(initial);
  const [specText, setSpecText] = useState(Object.entries(initial.spec).map(([k, v]) => `${k}: ${v}`).join("\n"));
  const set = <K extends keyof Content>(k: K, v: Content[K]) => setC({ ...c, [k]: v });
  const send = (e: FormEvent) => {
    e.preventDefault();
    const spec = Object.fromEntries(specText.split("\n").filter((l) => l.includes(":")).map((l) => {
      const i = l.indexOf(":");
      return [l.slice(0, i).trim(), l.slice(i + 1).trim()];
    }));
    onSubmit({ ...c, spec });
  };
  return (
    <form className="content-form" onSubmit={send}>
      <label className="field wide">제목<input value={c.title} onChange={(e) => set("title", e.target.value)} required /></label>
      <label className="field">분류<select value={c.category} onChange={(e) => set("category", e.target.value)}>{Object.entries(CATEGORY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label className="field">품목<select value={c.itemCode} onChange={(e) => set("itemCode", e.target.value)}><option value="">마스터에 없음</option>{catalog.items.map((i) => <option key={i.code} value={i.code}>{i.name}</option>)}</select></label>
      <label className="field">수량<input type="number" step="any" min="0" value={c.quantity} onChange={(e) => set("quantity", Number(e.target.value))} /></label>
      <label className="field">단가(원)<input type="number" min="0" value={c.unitPrice} onChange={(e) => set("unitPrice", Number(e.target.value))} /></label>
      <label className="field">공급사<select value={c.supplierCode} onChange={(e) => set("supplierCode", e.target.value)}>{catalog.suppliers.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}</select></label>
      <label className="field">과제<select value={c.projectCode} onChange={(e) => set("projectCode", e.target.value)}>{catalog.projects.map((p) => <option key={p.code} value={p.code}>{p.code} {p.name}</option>)}</select></label>
      <label className="field">희망 납기<input type="date" value={c.deliveryDate} onChange={(e) => set("deliveryDate", e.target.value)} /></label>
      <label className="check-line"><input type="checkbox" checked={c.soleSource} onChange={(e) => set("soleSource", e.target.checked)} /> 단독 구매</label>
      {c.soleSource && <label className="field wide">단독 구매 사유<input value={c.soleSourceReason} onChange={(e) => set("soleSourceReason", e.target.value)} /></label>}
      <label className="field wide">사양 (한 줄에 "이름: 값")<textarea rows={4} value={specText} onChange={(e) => setSpecText(e.target.value)} /></label>
      <button className="primary wide" type="submit">{submit}</button>
    </form>
  );
}

function Sheet({ id, user, catalog, onChange, onError }: { id: number; user: User; catalog: Catalog; onChange: () => Promise<void>; onError: (e: string) => void }) {
  const [d, setD] = useState<Detail | null>(null);
  const [editing, setEditing] = useState(false);
  const [comment, setComment] = useState("");
  const [suggestions, setSuggestions] = useState<Suggestions | null>(null);

  useEffect(() => { api.detail(id).then(setD).catch((e) => onError(e.message)); }, [id, onError]);

  const run = async (action: () => Promise<Detail>) => {
    onError("");
    try { setD(await action()); await onChange(); setComment(""); setEditing(false); setSuggestions(null); }
    catch (e) {
      onError((e as Error).message);
      setD(await api.detail(id));
    }
  };
  if (!d) return <p>불러오는 중입니다.</p>;

  const head = d.revisions[d.revisions.length - 1];
  const c = head.content;
  const myRole = d.requiredReviews.find((r) => r.role === user.role);
  const isRequester = user.id === d.requester.id;
  const canMerge = d.status === "OPEN" && (isRequester || user.role === "PROCUREMENT");
  const canReview = d.status === "OPEN" && !!myRole && !isRequester;
  const applyChanges = () => {
    const next: Content = { ...c, spec: { ...c.spec } };
    for (const s of suggestions?.changes ?? []) {
      if (s.field.startsWith("spec.")) next.spec[s.field.slice(5)] = s.value;
      else (next as unknown as Record<string, unknown>)[s.field] = s.value;
    }
    return run(() => api.revise(d.id, user.id, next));
  };

  return (
    <article className="sheet">
      <header className="sheet-head">
        <div>
          <p className="form-name">구매 품의서 #{d.id}, 리비전 {d.headRev}</p>
          <h1>{d.title}</h1>
          <p className="meta">{d.requester.dept} {d.requester.name}, {won(d.amount)}, <span className={`state ${d.status}`}>{STATUS[d.status]}</span></p>
        </div>
        <Approvals d={d} />
      </header>

      <section>
        <h2>체크</h2>
        <ul className="checks">
          {d.checks.map((k) => (
            <li key={k.id} className={k.status}>
              <span className="mark">{CHECK[k.status]}</span>
              <strong>{k.label}</strong>
              <span>{k.message}</span>
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2>사양</h2>
        <dl className="spec">
          <div><dt>품명</dt><dd>{c.itemName || "미지정"}</dd></div>
          <div><dt>분류</dt><dd>{CATEGORY[c.category]}</dd></div>
          <div><dt>수량</dt><dd>{c.quantity} {c.unit}</dd></div>
          <div><dt>단가</dt><dd>{won(c.unitPrice)}</dd></div>
          <div><dt>공급사</dt><dd>{catalog.suppliers.find((s) => s.code === c.supplierCode)?.name ?? c.supplierCode}</dd></div>
          <div><dt>과제</dt><dd>{c.projectCode}</dd></div>
          <div><dt>희망 납기</dt><dd>{c.deliveryDate || "없음"}</dd></div>
          {Object.entries(c.spec).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
        </dl>
        {c.quoteText && <details className="quote"><summary>견적서 원문</summary><pre>{c.quoteText}</pre></details>}
      </section>

      <RevisionDiffs revisions={d.revisions} />

      {d.status === "OPEN" && (
        <section className="actions">
          <h2>처리</h2>
          {d.merge.blockers.length > 0 && (
            <ul className="blockers">{d.merge.blockers.map((b) => <li key={b}>{humanize(b)}</li>)}</ul>
          )}
          {canReview && (
            <div className="review-box">
              <label className="field">{ROLE[user.role]} 의견<textarea rows={2} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
              <div className="row">
                <button className="primary" onClick={() => run(() => api.review(d.id, user.id, { role: user.role, decision: "APPROVED", comment }))}>승인</button>
                <button className="ghost" onClick={() => run(() => api.review(d.id, user.id, { role: user.role, decision: "CHANGES_REQUESTED", comment }))}>변경 요청</button>
              </div>
            </div>
          )}
          {!canReview && !isRequester && !canMerge && <p className="hint">이 요청에서 {user.name}님이 할 일은 없습니다. 위쪽에서 리뷰어로 사용자를 바꿔 보세요.</p>}
          {isRequester && <p className="hint">본인 요청은 승인할 수 없습니다. 리뷰어로 사용자를 바꿔 승인해 보세요.</p>}
          <div className="row">
            {canMerge && <button className="merge" disabled={!d.merge.allowed} onClick={() => run(() => api.merge(d.id, user.id))}>발주로 전환</button>}
            {isRequester && <button className="ghost" onClick={() => setEditing(!editing)}>사양 고치기</button>}
            {isRequester && !d.merge.allowed && d.checks.some((k) => k.status === "FAIL") && (
              <button className="ghost" onClick={async () => { try { setSuggestions(await api.suggest(d.id)); } catch (e) { onError((e as Error).message); } }}>AI 수정 제안</button>
            )}
            {isRequester && <button className="ghost" onClick={() => run(() => api.close(d.id, user.id))}>요청 닫기</button>}
          </div>
          {suggestions && (
            <div className="suggestions">
              <p>{suggestions.summary}</p>
              <ul>{suggestions.changes.map((s) => <li key={s.field}><strong>{s.field}</strong> {s.value}<br /><span>{s.reason}</span></li>)}</ul>
              {suggestions.changes.length > 0 && <button className="primary" onClick={applyChanges}>제안을 새 리비전으로 올리기</button>}
            </div>
          )}
          {editing && <ContentForm catalog={catalog} initial={c} submit="새 리비전 올리기" onSubmit={(next) => run(() => api.revise(d.id, user.id, next))} />}
        </section>
      )}
      {d.orderId && <p className="ordered">발주 번호 PO-{d.orderId}로 전환했습니다.</p>}

      <section>
        <h2>이력</h2>
        <ol className="events">
          {d.events.map((e, i) => <li key={i}><time>{e.createdAt}</time><strong>{EVENT[e.type] ?? e.type}</strong><span>{catalog.users.find((u) => u.id === e.actorId)?.name} {humanize(e.detail)}</span></li>)}
        </ol>
      </section>
    </article>
  );
}

function Approvals({ d }: { d: Detail }) {
  const latest = (role: string): Review | undefined => [...d.reviews].reverse().find((r) => r.role === role && r.current);
  const stale = (role: string) => !latest(role) && d.reviews.some((r) => r.role === role);
  return (
    <table className="approvals" aria-label="결재란">
      <thead><tr>{d.requiredReviews.map((r) => <th key={r.role} scope="col" title={r.reasons.join(", ")}>{ROLE[r.role] ?? r.role}</th>)}</tr></thead>
      <tbody><tr>
        {d.requiredReviews.map((r) => {
          const rev = latest(r.role);
          return (
            <td key={r.role}>
              {rev?.decision === "APPROVED" && <span className="stamp">{rev.reviewerName}</span>}
              {rev?.decision === "CHANGES_REQUESTED" && <span className="hold">변경 요청</span>}
              {!rev && <span className="wait">{stale(r.role) ? "재승인 필요" : "대기"}</span>}
            </td>
          );
        })}
      </tr></tbody>
    </table>
  );
}

function RevisionDiffs({ revisions }: { revisions: Revision[] }) {
  if (revisions.length < 2) return null;
  const pairs = revisions.slice(1).map((r, i) => ({ from: revisions[i], to: r })).reverse();
  return (
    <section>
      <h2>변경 내역</h2>
      {pairs.map(({ from, to }) => (
        <div key={to.revNo} className="diff">
          <p>리비전 {from.revNo}에서 {to.revNo}로, {to.createdAt}</p>
          {diff(from.content, to.content).map((x) => (
            <div key={x.key} className="diff-row">
              <span>{x.key}</span>
              <del>{x.before || "없음"}</del>
              <ins>{x.after || "없음"}</ins>
            </div>
          ))}
        </div>
      ))}
    </section>
  );
}
