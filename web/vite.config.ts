import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { defineConfig } from "vite";

const directory = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const coreDirectory = path.dirname(require.resolve("@litert-lm/core/package.json"));
const wasmDirectory = path.join(coreDirectory, "wasm");
const licensePath = path.resolve(directory, "..", "LICENSE");

const securityHeaders = {
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Embedder-Policy": "require-corp",
  "Cross-Origin-Resource-Policy": "same-origin",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()",
};

export default defineConfig({
  base: "./",
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: "assets/app.js",
        chunkFileNames: "assets/[name].js",
        assetFileNames: (asset) =>
          asset.names.some((name) => name.endsWith(".css"))
            ? "assets/app.css"
            : "assets/[name][extname]",
      },
    },
  },
  worker: {
    format: "iife",
    rollupOptions: {
      output: {
        entryFileNames: "assets/model-worker.js",
        chunkFileNames: "assets/worker-[name].js",
      },
    },
  },
  server: {
    host: "127.0.0.1",
    port: 4173,
    headers: securityHeaders,
  },
  preview: {
    host: "127.0.0.1",
    port: 4173,
    headers: securityHeaders,
  },
  plugins: [
    {
      name: "tapziq-litert-runtime",
      configureServer(server) {
        server.middlewares.use((request, response, next) => {
          const pathname = new URL(request.url ?? "/", "http://localhost").pathname;
          if (pathname === "/LICENSE.txt") {
            response.setHeader("Content-Type", "text/plain; charset=utf-8");
            response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
            fs.createReadStream(licensePath).pipe(response);
            return;
          }
          if (!pathname.startsWith("/wasm/")) {
            next();
            return;
          }
          const filename = path.basename(pathname);
          const runtimePath = path.join(wasmDirectory, filename);
          if (!fs.existsSync(runtimePath)) {
            response.statusCode = 404;
            response.end();
            return;
          }
          response.setHeader(
            "Content-Type",
            filename.endsWith(".wasm") ? "application/wasm" : "text/javascript",
          );
          response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
          fs.createReadStream(runtimePath).pipe(response);
        });
      },
      closeBundle() {
        const destination = path.join(directory, "dist", "wasm");
        fs.mkdirSync(destination, { recursive: true });
        for (const filename of fs.readdirSync(wasmDirectory)) {
          fs.copyFileSync(path.join(wasmDirectory, filename), path.join(destination, filename));
        }
        fs.copyFileSync(licensePath, path.join(directory, "dist", "LICENSE.txt"));
      },
    },
  ],
});
