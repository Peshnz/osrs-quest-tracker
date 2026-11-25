// Kruger tick list service worker. VERSION changes automatically whenever any app file changes,
// so phones fetch the new version next time they're online, then show "Update now".
const VERSION = "kruger-f823f5d49d";
const FILES = ['index.html', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/icon-maskable-512.png', 'icons/apple-touch-icon.png', 'img/bateleur-m.jpg'];
self.addEventListener("install", e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(FILES.map(f => new Request(f, {cache: "reload"})))));
});
self.addEventListener("message", e => { if (e.data === "skip") self.skipWaiting(); });
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k.startsWith("kruger-") && k !== VERSION && k !== "kruger-fonts").map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  // Fonts: keep a copy so the safari lettering works offline.
  if (url.hostname === "fonts.googleapis.com" || url.hostname === "fonts.gstatic.com") {
    e.respondWith(caches.open("kruger-fonts").then(c => c.match(req).then(hit => {
      const net = fetch(req).then(res => { c.put(req, res.clone()); return res; }).catch(() => hit);
      return hit || net;
    })));
    return;
  }
  if (url.origin !== location.origin) return;
  // App files: answer instantly from the saved copy (works with no signal); updates arrive via a new VERSION.
  e.respondWith(caches.open(VERSION).then(c => c.match(req, {ignoreSearch: true}).then(hit => hit || fetch(req).then(res => {
    if (res.ok && url.pathname.includes("/kruger/")) c.put(req, res.clone());
    return res;
  }).catch(() => req.mode === "navigate" ? c.match("index.html") : Response.error()))));
});
