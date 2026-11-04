import { execSync } from "node:child_process";

execSync("npx vite build --outDir dist-demo", {
  stdio: "inherit",
  env: { ...process.env, VITE_DEMO: "1", VITE_BASE: process.env.VITE_BASE ?? "./" },
});
