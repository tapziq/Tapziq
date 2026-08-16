const SHELL_CACHE = "tapziq-browser-shell-v7";
const scopeUrl = new URL(self.registration.scope);
const indexUrl = new URL("./index.html", scopeUrl);
const shellUrls = [
  "./",
  "./index.html",
  "./assets/app.js",
  "./assets/app.css",
  "./assets/model-worker.js",
  "./manifest.webmanifest",
  "./icons/tapziq.svg",
  "./docs.css",
  "./README.html",
  "./PRIVACY.html",
  "./THIRD_PARTY_NOTICES.html",
  "./LICENSE.txt",
].map((path) => new URL(path, scopeUrl).href);

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL_CACHE).then((cache) => cache.addAll(shellUrls)));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names
      .filter((name) => name.startsWith("tapziq-browser-shell-") && name !== SHELL_CACHE)
      .map((name) => caches.delete(name)));
    await self.clients.claim();
  })());
});

function cacheable(request, url) {
  return request.destination === "script"
    || request.destination === "style"
    || request.destination === "worker"
    || request.destination === "manifest"
    || request.destination === "image"
    || url.pathname.includes("/wasm/");
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin) {
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith((async () => {
      const appRoot = scopeUrl.pathname.endsWith("/")
        ? scopeUrl.pathname
        : `${scopeUrl.pathname}/`;
      const isAppNavigation = url.pathname === appRoot
        || url.pathname === indexUrl.pathname;
      const exactUrl = new URL(url.pathname, url.origin);
      try {
        const response = await fetch(request);
        if (response.ok && isAppNavigation) {
          const cache = await caches.open(SHELL_CACHE);
          await cache.put(indexUrl, response.clone());
        }
        return response;
      } catch {
        const exact = await caches.match(exactUrl);
        if (exact !== undefined) {
          return exact;
        }
        if (isAppNavigation) {
          const cachedIndex = await caches.match(indexUrl);
          return cachedIndex ?? Response.error();
        }
        return Response.error();
      }
    })());
    return;
  }

  if (!cacheable(request, url)) {
    return;
  }
  event.respondWith((async () => {
    const cached = await caches.match(request);
    if (cached !== undefined) {
      return cached;
    }
    const response = await fetch(request);
    if (response.ok) {
      const cache = await caches.open(SHELL_CACHE);
      await cache.put(request, response.clone());
    }
    return response;
  })());
});
