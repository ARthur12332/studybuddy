/* StudyBuddy Service Worker —— 让页面离线可用、可从主屏幕直接打开
 *
 * ⚠️ v7 起换缓存策略（v8/v9 沿用，只随版本号提升清一次旧缓存）（2026-09-23 踩坑后改的，别改回去）：
 *   旧版对**所有**请求都「缓存优先」，结果发新版后手机上永远是旧页面 ——
 *   刷新也没用，因为请求根本没出网。现在分两种：
 *   · HTML 导航 → **网络优先**：联网就先拿线上最新页面，顺手更新缓存；
 *     只有断网 / 请求失败才回落到缓存（离线可用性靠这个兜底）。
 *   · 其它静态资源（图标等）→ 仍然缓存优先，省请求。
 *   另外安装时**不再预缓存 index.html**：否则安装那一刻正好拿到旧副本，就会被钉死在缓存里。
 */
const CACHE = "studybuddy-v9";
const ASSETS = [
  "./manifest.webmanifest",
  "./icons/icon-192.png",
  "./icons/icon-512.png"
];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => Promise.all(ASSETS.map((u) => c.add(u).catch(() => null))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

/* 判断是不是「要 HTML 的导航请求」：mode 与 Accept 头各判一次更稳 */
function isHTML(req){
  if (req.mode === "navigate") return true;
  return (req.headers.get("accept") || "").indexOf("text/html") >= 0;
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== location.origin) return;

  if (isHTML(req)) {
    e.respondWith(
      fetch(req)
        .then((resp) => {
          if (resp && resp.status === 200 && resp.type === "basic") {
            const copy = resp.clone();
            caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
          }
          return resp;
        })
        /* 离线时：先用这次导航地址的缓存，再退到 ./index.html */
        .catch(() => caches.match(req).then((hit) => hit || caches.match("./index.html")))
    );
    return;
  }

  e.respondWith(
    caches.match(req).then((hit) => {
      if (hit) return hit;
      return fetch(req)
        .then((resp) => {
          if (resp && resp.status === 200 && resp.type === "basic") {
            const copy = resp.clone();
            caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => caches.match("./index.html"));
    })
  );
});
