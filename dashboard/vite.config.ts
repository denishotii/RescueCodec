import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

// The build goes straight into the gateway's static folder.
// The gateway serves it at "/" and the API under "/v2" on the same origin.
const out = fileURLToPath(new URL("../rescuecodec/gateway/static", import.meta.url));
const api = process.env.RC_API ?? "http://127.0.0.1:8000";

export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: out,
    emptyOutDir: false, // scripts/copy-map.mjs adds map files after the build; clean in the script
    target: "es2022",
    chunkSizeWarningLimit: 1500,
  },
  server: {
    port: 5173,
    proxy: {
      "/v2": { target: api, ws: true, changeOrigin: true },
      "/map": { target: api, changeOrigin: true },
    },
  },
  test: { include: ["tests/**/*.test.ts"] },
});
