import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  base: process.env.VITE_BASE ?? "/",
  plugins: [react()],
  server: {
    port: 5173,
    fs: { allow: [".."] },
    proxy: { "/api": "http://localhost:8080" },
  },
});
