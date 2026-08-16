import assert from "node:assert/strict";
import path from "node:path";

import { chromium } from "@playwright/test";

if (process.env.TAPZIQ_REAL_MODEL_TEST !== "1") {
  throw new Error(
    "Set TAPZIQ_REAL_MODEL_TEST=1 to acknowledge the 2.01 GB browser-model download.",
  );
}

const profilePath = process.env.TAPZIQ_CHROME_PROFILE;
if (profilePath === undefined || !path.isAbsolute(profilePath)) {
  throw new Error("TAPZIQ_CHROME_PROFILE must be an explicit absolute test-profile path.");
}

const baseUrl = process.env.TAPZIQ_BASE_URL ?? "http://127.0.0.1:4173/";
const headless = process.env.TAPZIQ_HEADFUL !== "1";
const expectedModel = {
  directory: "tapziq-browser-models",
  filename: "gemma-4-E2B-it-web.litertlm",
  metadata: "gemma-4-E2B-it-web.verified.json",
  bytes: 2_008_432_640,
  sha256: "3a08e8d94e23b814ae5414469c370c503813949acb8ceaa17e4ebf8a35af35b5",
};
const context = await chromium.launchPersistentContext(profilePath, {
  channel: "chrome",
  headless,
  viewport: { width: 1360, height: 900 },
});
const page = context.pages()[0] ?? await context.newPage();
page.setDefaultTimeout(30_000);

const browserErrors = [];
const runtimeDiagnostics = [];
const requests = [];
page.on("console", (message) => {
  if (message.type() === "error") {
    if (/^(?:INFO|WARNING): \[[^\]]+\.cc:\d+\]/.test(message.text())) {
      runtimeDiagnostics.push(message.text());
    } else {
      browserErrors.push(message.text());
    }
  }
});
page.on("pageerror", (error) => browserErrors.push(error.message));
page.on("request", (request) => requests.push({
  method: request.method(),
  url: request.url(),
  body: request.postData() ?? "",
}));

async function statusSnapshot() {
  return page.evaluate(() => ({
    badge: document.querySelector("#model-badge")?.textContent?.trim() ?? "",
    detail: document.querySelector("#download-detail")?.textContent?.trim() ?? "",
    status: document.querySelector("#status")?.textContent?.trim() ?? "",
  }));
}

async function waitForInitialSetup() {
  await page.waitForFunction(() => {
    const badge = document.querySelector("#model-badge")?.textContent ?? "";
    const status = document.querySelector("#status")?.textContent ?? "";
    const download = document.querySelector("#download-button");
    const downloadReady = download instanceof HTMLButtonElement
      && !download.hidden
      && !download.disabled
      && /Download verified model|Resume verified download/.test(download.textContent ?? "");
    return badge.includes("Verified")
      || downloadReady
      || /cannot run local Gemma|Browser setup failed/i.test(status);
  }, undefined, { timeout: 60_000 });

  const snapshot = await statusSnapshot();
  if (/cannot run local Gemma|Browser setup failed/i.test(snapshot.status)) {
    throw new Error(snapshot.status);
  }
  return snapshot;
}

async function waitForModelReady() {
  const deadline = Date.now() + 45 * 60_000;
  let previous = "";
  while (Date.now() < deadline) {
    const snapshot = await statusSnapshot();
    const serialized = JSON.stringify(snapshot);
    if (serialized !== previous) {
      console.log(new Date().toISOString(), serialized);
      previous = serialized;
    }
    if (snapshot.badge.includes("Verified")) {
      return;
    }
    if (/failed|not enough|cannot run/i.test(snapshot.status)) {
      throw new Error(snapshot.status);
    }
    await page.waitForTimeout(5_000);
  }
  throw new Error("Timed out waiting for the verified browser model.");
}

async function proofread(source) {
  const editor = page.getByRole("textbox", { name: "Type or paste text" });
  await editor.fill(source);
  await page.getByRole("button", { name: "Proofread with browser Gemma 4" }).click();
  await page.waitForFunction(() => {
    const status = document.querySelector("#status")?.textContent ?? "";
    return /Review the exact suggestion|found no safe|failed safely/.test(status);
  }, undefined, { timeout: 15 * 60_000 });
  const snapshot = await statusSnapshot();
  if (snapshot.status.includes("failed safely")) {
    throw new Error(snapshot.status);
  }
  const preview = await page.locator("#preview-text").textContent() ?? "";
  assert.notEqual(preview.trim(), "", "The real model did not produce a visible suggestion.");
  await page.getByRole("button", { name: "Apply" }).click();
  return { preview, applied: await editor.inputValue() };
}

try {
  await page.goto(baseUrl, { waitUntil: "networkidle" });
  const capabilities = await page.evaluate(async () => {
    const adapter = "gpu" in navigator
      ? await navigator.gpu.requestAdapter({ powerPreference: "high-performance" })
      : null;
    return {
      secureContext: isSecureContext,
      webGpu: "gpu" in navigator,
      gpuAdapter: adapter !== null,
      adapterInfo: adapter?.info ?? null,
      opfs: "getDirectory" in navigator.storage,
      webLocks: "locks" in navigator,
    };
  });
  assert.equal(capabilities.secureContext, true);
  assert.equal(capabilities.webGpu, true);
  assert.equal(capabilities.gpuAdapter, true);
  assert.equal(capabilities.opfs, true);
  assert.equal(capabilities.webLocks, true);

  const state = await waitForInitialSetup();
  assert.match(state.status, /Browser checks passed|verified Gemma 4 model found/i);
  if (!state.badge.includes("Verified")) {
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: /Download verified model|Resume verified download/ })
      .click();
    await waitForModelReady();
  }

  const online = await proofread("thiss is bad grammer.");
  await page.evaluate(async () => navigator.serviceWorker.ready);
  await page.reload({ waitUntil: "networkidle" });
  await context.setOffline(true);
  await page.reload({ waitUntil: "domcontentloaded" });
  await waitForModelReady();
  const offline = await proofread("teh cats is here.");
  const storedModel = await page.evaluate(async (expected) => {
    const root = await navigator.storage.getDirectory();
    const directory = await root.getDirectoryHandle(expected.directory);
    const model = await (await directory.getFileHandle(expected.filename)).getFile();
    const metadataFile = await (await directory.getFileHandle(expected.metadata)).getFile();
    const metadata = JSON.parse(await metadataFile.text());
    return {
      bytes: model.size,
      lastModified: model.lastModified,
      metadata,
      persistentStorage: await navigator.storage.persisted(),
      shellCaches: await caches.keys(),
      serviceWorkerControlled: navigator.serviceWorker.controller !== null,
    };
  }, expectedModel);
  assert.equal(storedModel.bytes, expectedModel.bytes);
  assert.equal(storedModel.metadata.bytes, expectedModel.bytes);
  assert.equal(storedModel.metadata.sha256, expectedModel.sha256);
  assert.equal(storedModel.metadata.lastModified, storedModel.lastModified);
  assert.equal(storedModel.serviceWorkerControlled, true);

  for (const request of requests) {
    const decoded = decodeURIComponent(`${request.url}\n${request.body}`).toLowerCase();
    assert.doesNotMatch(decoded, /thiss is bad grammer|teh cats is here/);
  }
  assert.equal(
    requests.some((request) => request.method !== "GET" && request.method !== "HEAD"),
    false,
    "The browser check observed an unexpected request with a body.",
  );
  for (const request of requests) {
    const host = new URL(request.url).hostname;
    assert.equal(
      host === "127.0.0.1" || host === "huggingface.co" || host.endsWith(".hf.co"),
      true,
      `Unexpected network host: ${host}`,
    );
  }
  assert.deepEqual(browserErrors, [], "The browser emitted console or page errors.");

  console.log(JSON.stringify({
    capabilities,
    online,
    offline,
    storedModel,
    observedRequestOrigins: [...new Set(requests.map((request) => new URL(request.url).origin))],
    browserErrors,
    runtimeDiagnostics: [...new Set(runtimeDiagnostics)],
  }, null, 2));
} catch (error) {
  await page.screenshot({ path: "/tmp/tapziq-real-browser-failure.png", fullPage: true })
    .catch(() => undefined);
  throw error;
} finally {
  await context.close();
}
