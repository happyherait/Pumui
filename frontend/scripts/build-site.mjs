import { execSync } from "node:child_process";
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { marked } from "marked";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const out = resolve(root, ".site");
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

const repo = "https://github.com/happyherait/pumui/blob/main/docs/";
const qna = marked.parse(readFileSync(resolve(root, "docs/qna.md"), "utf8")).replaceAll('href="adr/', `href="${repo}adr/`);
writeFileSync(resolve(out, "index.html"), readFileSync(resolve(root, "site/template.html"), "utf8").replace("{{QNA}}", () => qna));
cpSync(resolve(root, "site/styles.css"), resolve(out, "styles.css"));

execSync("node scripts/build-demo.mjs", { cwd: resolve(root, "frontend"), stdio: "inherit", env: { ...process.env, VITE_BASE: "./" } });
cpSync(resolve(root, "frontend/dist-demo"), resolve(out, "demo"), { recursive: true });
