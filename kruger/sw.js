// The Kruger tick list moved to https://peshnz.github.io/kruger-tick-list/
// This replacement worker clears the old offline copy and removes itself.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k.startsWith("kruger-")).map(k => caches.delete(k))))
    .then(() => self.registration.unregister())
    .then(() => self.clients.matchAll()).then(cs => cs.forEach(c => c.navigate(c.url))));
});
