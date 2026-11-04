// 공개 데모 시나리오 검증. 사용법: node e2e/demo.mjs <url> [screenshot-dir]
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "playwright");
const [url, shots] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
const as = (name) => page.selectOption("select >> nth=0", { label: name });
const shot = (n) => (shots ? page.screenshot({ path: `${shots}/${n}.png`, fullPage: true }) : null);
const ok = (cond, label) => { if (!cond) throw new Error(`실패: ${label}`); console.log(`통과: ${label}`); };

await page.goto(url);
await page.getByRole("heading", { name: /NMC811/ }).waitFor();
ok(await page.locator(".merge").isDisabled(), "리뷰 전 발주 버튼 비활성");
await as("이팀장 (팀장)");
await page.getByRole("button", { name: "승인", exact: true }).click();
await page.locator(".stamp").waitFor();
await as("김연구 (연구원)");
await page.locator(".merge").click();
await page.locator(".ordered").waitFor();
ok(true, "승인 후 발주 전환");
await shot("merged");

await page.getByRole("link", { name: "새 구매요청" }).click();
await page.getByRole("button", { name: "숨은 지시가 든 견적서" }).click();
await page.getByRole("button", { name: "AI 초안 만들기" }).click();
await page.getByRole("button", { name: "구매요청 열기" }).click();
await page.getByRole("heading", { name: /고순도 염/ }).waitFor();
ok((await page.locator(".approvals").innerText()).includes("안전환경"), "숨은 CAS로 안전환경 리뷰 지정");
ok((await page.locator(".checks .REVIEW").first().innerText()).includes("21324-40-3"), "규제 물질 탐지");
await shot("attack");

await as("이팀장 (팀장)");
await page.getByRole("button", { name: "승인", exact: true }).click();
await page.locator(".stamp").waitFor();
await as("김연구 (연구원)");
await page.getByRole("button", { name: "사양 고치기" }).click();
await page.locator(".content-form input[type=number]").nth(0).fill("3");
await page.getByRole("button", { name: "새 리비전 올리기" }).click();
await page.getByText("재승인 필요").first().waitFor();
ok((await page.locator(".diff").innerText()).includes("수량"), "새 리비전이 승인을 무효화하고 변경을 표시");
await shot("revised");

await page.setViewportSize({ width: 390, height: 844 });
ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "휴대폰 폭 가로 스크롤 없음");
await shot("mobile");
ok(errors.length === 0, `콘솔 오류 없음 ${errors.join(" | ")}`);
await browser.close();
