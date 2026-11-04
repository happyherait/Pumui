// 공개 데모를 자동 시연하며 프레임과 자막 시간표를 저장한다.
// 사용법: node e2e/record-demo.mjs <출력 폴더> <실제 실행 화면 폴더>
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "playwright");
const [outDir, realDir] = process.argv.slice(2).map((p) => resolve(p));
const frames = resolve(outDir, "frames");
mkdirSync(frames, { recursive: true });

const URL_ = "http://localhost:4199/";
const server = spawn("npx", ["vite", "preview", "--outDir", "dist-demo", "--port", "4199", "--strictPort"], { stdio: "ignore", detached: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
for (let i = 0; i < 40; i++) {
  if (await fetch(URL_).then((r) => r.ok).catch(() => false)) break;
  await sleep(250);
}

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1920, height: 960 } });
const page = await context.newPage();
await page.addInitScript(() => {
  addEventListener("DOMContentLoaded", () => {
    const style = document.createElement("style");
    style.textContent = `
      #root, #demo-card { zoom: 1.5; }
      #demo-cursor { position: fixed; z-index: 100000; left: 960px; top: 480px; width: 28px; height: 28px; border-radius: 50%; background: rgba(31,63,191,.35); border: 2px solid #1f3fbf; transition: left .5s ease, top .5s ease, transform .12s; pointer-events: none; }
      #demo-ring { position: fixed; z-index: 99999; border: 4px solid #c2231f; border-radius: 4px; pointer-events: none; transition: opacity .2s; }
      #demo-card { position: fixed; inset: 0; z-index: 100001; background: #16263a; color: #eef3f6; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 18px; font-family: "Gowun Batang", serif; text-align: center; }
      #demo-card h1 { font-size: 64px; margin: 0; font-weight: 700; }
      #demo-card h2 { font-size: 34px; margin: 0; font-weight: 400; }
      #demo-card p { font: 400 20px/1.7 "IBM Plex Sans KR", sans-serif; margin: 0; color: #c5d1da; }
      #demo-card table { border-collapse: collapse; font: 400 21px/1.5 "IBM Plex Sans KR", sans-serif; text-align: left; }
      #demo-card td { padding: 8px 44px 8px 0; border-bottom: 1px solid #5d6f80; }
      #demo-card .pics { display: flex; gap: 20px; align-items: center; justify-content: center; }
      #demo-card img { max-height: 600px; max-width: 610px; border: 2px solid #9fb0ba; background: #fff; }
      #demo-card img.top { height: 600px; width: 520px; object-fit: cover; object-position: top; }
      #demo-card .nums { display: flex; gap: 64px; }
      #demo-card .nums b { display: block; font-size: 58px; }
      #demo-card .nums span { font: 400 19px/1.5 "IBM Plex Sans KR", sans-serif; color: #c5d1da; }
    `;
    document.head.append(style);
    const cursor = document.createElement("div");
    cursor.id = "demo-cursor";
    document.body.append(cursor);
  });
});

const cdp = await context.newCDPSession(page);
const log = [];
let start = 0;
let index = 0;
cdp.on("Page.screencastFrame", async ({ data, sessionId, metadata }) => {
  const file = `f${String(index++).padStart(6, "0")}.jpg`;
  writeFileSync(resolve(frames, file), Buffer.from(data, "base64"));
  log.push({ file, t: metadata.timestamp * 1000 });
  await cdp.send("Page.screencastFrameAck", { sessionId }).catch(() => {});
});

const captions = [];
const now = () => Date.now() - start;
async function say(text, ms) {
  const from = now();
  await sleep(ms);
  captions.push({ from, to: now(), text });
}
const card = (html) => page.evaluate((h) => {
  document.getElementById("demo-card")?.remove();
  if (!h) return;
  const el = document.createElement("div");
  el.id = "demo-card";
  el.innerHTML = h;
  document.body.append(el);
}, html);
async function moveTo(locator) {
  const box = await locator.boundingBox();
  await page.evaluate(([x, y]) => {
    const c = document.getElementById("demo-cursor");
    c.style.left = `${x - 14}px`;
    c.style.top = `${y - 14}px`;
  }, [box.x + box.width / 2, box.y + box.height / 2]);
  await sleep(620);
}
async function click(locator) {
  await locator.scrollIntoViewIfNeeded();
  await moveTo(locator);
  await locator.click();
  await sleep(500);
}
const ring = async (locator) => {
  const box = await locator.boundingBox();
  await page.evaluate((b) => {
    document.getElementById("demo-ring")?.remove();
    const r = document.createElement("div");
    r.id = "demo-ring";
    Object.assign(r.style, { left: `${b.x - 5}px`, top: `${b.y - 5}px`, width: `${b.width + 10}px`, height: `${b.height + 10}px` });
    document.body.append(r);
  }, box);
};
const unring = () => page.evaluate(() => document.getElementById("demo-ring")?.remove());
async function scrollTo(locator, offset = 80) {
  await unring();
  await page.evaluate(([y]) => window.scrollTo({ top: y, behavior: "smooth" }), [
    await locator.evaluate((el, o) => el.getBoundingClientRect().top + scrollY - o, offset * 1.5),
  ]);
  await sleep(900);
}
const asUser = async (label) => {
  await page.evaluate(() => window.scrollTo({ top: 0 }));
  await sleep(200);
  const select = page.locator("select").first();
  await moveTo(select);
  await select.selectOption({ label });
  await sleep(600);
};
const b64 = (f) => `data:image/png;base64,${readFileSync(resolve(realDir, f)).toString("base64")}`;

await page.goto(URL_);
await page.getByRole("heading", { name: /NMC811/ }).waitFor();
await page.evaluate(() => document.fonts.ready);
await card(`<h1>품의</h1><h2>구매요청을 Pull Request처럼</h2>`);
await sleep(300);
await cdp.send("Page.startScreencast", { format: "jpeg", quality: 92, maxWidth: 1920, maxHeight: 960, everyNthFrame: 1 });
await sleep(300);
start = Date.now();

await say("연구소 구매요청을 GitHub의 Pull Request처럼 다루는 프로토타입입니다.", 4000);
await say("회사, 데이터, 규제 등급은 모두 가상입니다.\n특정 회사의 시스템과 관련이 없습니다.", 4200);
await card(`<table>
  <tr><td>Pull Request</td><td>구매요청</td></tr>
  <tr><td>Commit</td><td>사양 리비전</td></tr>
  <tr><td>Checks</td><td>예산, 사양, 규제, 중복, 단가 체크</td></tr>
  <tr><td>CODEOWNERS</td><td>PROCUREMENTOWNERS 리뷰어 규칙</td></tr>
  <tr><td>Merge</td><td>발주 전환</td></tr></table>`);
await say("GitHub의 체크, 리뷰어 지정, 승인, 머지를 구매 절차에 옮겼습니다.", 4800);
await say("AI는 초안과 수정안만 제안합니다.\n통과 판단과 발주 허가는 서버 코드가 맡습니다.", 5000);
await card("");
await say("이 영상은 서버 없이 도는 공개 데모로 녹화했습니다.\nAI 초안은 미리 준비한 값이고, 실제 모델 결과는 뒤에서 보여 줍니다.", 6000);

await ring(page.locator(".checks"));
await say("연구원의 구매요청입니다. 요청마다 체크 10종이 자동으로 실행됩니다.", 4800);
await ring(page.locator(".approvals"));
await say("리뷰어는 규칙 파일이 정합니다. 이 요청은 팀장 승인이 필요합니다.", 4500);
await scrollTo(page.locator(".actions"), 90);
await ring(page.locator(".merge"));
await say("승인 전에는 발주로 전환할 수 없습니다.", 3500);
await unring();
await asUser("이팀장 (팀장)");
await scrollTo(page.locator(".actions"), 90);
await say("팀장으로 접속해 승인합니다.", 2200);
await click(page.getByRole("button", { name: "승인", exact: true }));
await page.locator(".stamp").waitFor();
await page.evaluate(() => window.scrollTo({ top: 0, behavior: "smooth" }));
await sleep(800);
await ring(page.locator(".approvals"));
await say("결재란에 도장이 찍혔습니다.", 2800);
await unring();
await asUser("김연구 (연구원)");
await click(page.locator(".merge"));
await page.locator(".ordered").waitFor();
await scrollTo(page.locator(".ordered"), 240);
await ring(page.locator(".ordered"));
await say("모든 조건이 끝나 발주로 전환했습니다.", 3200);
await say("실제 서버는 행 잠금 안에서 체크를 다시 실행하고\nOracle 제약으로 예산 초과와 중복 발주를 막습니다.", 5500);
await unring();

await page.evaluate(() => window.scrollTo({ top: 0, behavior: "smooth" }));
await sleep(500);
await say("이번에는 공격입니다. 견적서에 숨은 지시를 넣었습니다.", 3500);
await click(page.getByRole("link", { name: "새 구매요청" }));
await click(page.getByRole("button", { name: "숨은 지시가 든 견적서" }));
await ring(page.locator("textarea").nth(1));
await say("소모품으로 쓰고 CAS를 빼고\n안전환경 검토를 생략하라는 내용이 들어 있습니다.", 5500);
await unring();
await click(page.getByRole("button", { name: "AI 초안 만들기" }));
await scrollTo(page.locator(".content-form"), 80);
await ring(page.locator(".content-form .field").nth(1));
await say("모델이 완전히 속은 최악의 경우를 가정한 초안입니다.\n분류가 소모품이고 CAS도 사라졌습니다.", 6000);
await unring();
await click(page.getByRole("button", { name: "구매요청 열기" }));
await page.getByRole("heading", { name: /고순도 염/ }).waitFor();
await page.evaluate(() => window.scrollTo({ top: 0, behavior: "smooth" }));
await sleep(900);
await ring(page.locator(".approvals"));
await say("그래도 결재란에 안전환경 리뷰가 붙습니다.", 3800);
await ring(page.locator(".checks .REVIEW").first());
await say("서버가 견적서 원문에서 CAS 번호를 직접 찾아\n규제 물질 표와 대조했기 때문입니다.", 5500);
await unring();

await asUser("이팀장 (팀장)");
await click(page.getByRole("button", { name: "승인", exact: true }));
await page.locator(".stamp").waitFor();
await page.evaluate(() => window.scrollTo({ top: 0, behavior: "smooth" }));
await sleep(700);
await say("팀장이 승인했습니다. 그런데 승인 뒤에 사양이 바뀌면 어떻게 될까요?", 4500);
await asUser("김연구 (연구원)");
await click(page.getByRole("button", { name: "사양 고치기" }));
const qty = page.locator(".content-form input[type=number]").nth(0);
await qty.scrollIntoViewIfNeeded();
await sleep(300);
await qty.fill("3");
await ring(qty);
await say("요청자가 수량을 2kg에서 3kg으로 고칩니다.", 3500);
await unring();
await click(page.getByRole("button", { name: "새 리비전 올리기" }));
await page.getByText("재승인 필요").first().waitFor();
await page.evaluate(() => window.scrollTo({ top: 0, behavior: "smooth" }));
await sleep(800);
await ring(page.locator(".approvals"));
await say("승인은 리비전 해시에 묶여 있어 자동으로 무효가 됩니다.\nGitHub에서 새 커밋이 이전 승인을 무효화하는 것과 같습니다.", 6000);
await scrollTo(page.locator(".diff"), 120);
await ring(page.locator(".diff"));
await say("무엇이 바뀌었는지는 변경 내역으로 남습니다.", 3500);
await unring();

await card(`<div class="pics"><img src="${b64("real-draft.png")}"><img class="top" src="${b64("real-sheet.png")}"></div>`);
await sleep(500);
await say("여기부터는 내 PC에서 실제 claude-sonnet-5-5로 1회 실행한 화면입니다.\n모델은 숨은 지시를 따르지 않고 화학소재로 분류했습니다.", 6500);
await card(`<div class="pics"><img src="${b64("real-suggest.png")}"></div>`);
await sleep(300);
await say("수정 제안도 순도와 납기를 지어내지 않고 확인할 항목만 안내했습니다.\n반복 평가가 아니라 1회 수동 실행 결과입니다.", 6500);
await card(`<h2>GitHub Actions 검증</h2><div class="nums">
  <div><b>8</b><span>규칙 테스트</span></div>
  <div><b>13</b><span>Oracle 통합 테스트</span></div>
  <div><b>14</b><span>프런트 테스트</span></div></div>`);
await say("실제 Oracle 컨테이너에서 통합 테스트를 실행합니다.\n동시 발주 8건에도 발주는 1건만 만들어집니다.", 5500);
await card(`<h1>품의</h1><p>React, Spring Boot, Oracle, Claude API</p><p>github.com/happyherait/Pumui</p>`);
await say("코드와 공개 데모는 GitHub에서 볼 수 있습니다.", 4000);

await cdp.send("Page.stopScreencast");
await sleep(300);
const first = log[0].t;
writeFileSync(resolve(outDir, "timeline.json"), JSON.stringify({
  duration: now(), frames: log.map((f) => ({ file: f.file, t: Math.max(0, f.t - start) })), captions,
}));
console.log(`프레임 ${log.length}장, 길이 ${(now() / 1000).toFixed(1)}초, 첫 프레임 오프셋 ${first - start}ms`);
await browser.close();
process.kill(-server.pid);
