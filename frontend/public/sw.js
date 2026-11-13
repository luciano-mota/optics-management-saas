/*
 * Optics Management: service worker.
 * Guarda só o "esqueleto" do app (telas, estilos, ícones). Nunca guarda respostas da API nem dados da ótica.
 * Sem internet, abre a página offline.html. Recebe as notificações push (texto genérico, sem dados de cliente).
 */
const CACHE = 'optics-shell-v3';
const SHELL = ['/offline.html', '/icon.svg', '/icon-192.png', '/manifest.webmanifest'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')) return; // dados: sempre da rede
  if (request.mode === 'navigate') {
    // Página: sempre a versão nova da rede; sem conexão, a tela offline.
    event.respondWith(fetch(request).catch(() => caches.match('/offline.html')));
    return;
  }
  if (url.pathname.startsWith('/assets/') || SHELL.includes(url.pathname) || /\.(png|svg|webmanifest)$/.test(url.pathname)) {
    // Arquivos estáticos com hash no nome: do cache, atualizando em segundo plano.
    event.respondWith(caches.open(CACHE).then(async cache => {
      const cached = await cache.match(request);
      const network = fetch(request).then(response => {
        if (response.ok) cache.put(request, response.clone());
        return response;
      }).catch(() => cached);
      return cached || network;
    }));
  }
});

self.addEventListener('push', event => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = {}; }
  const title = data.title || 'Optics Management';
  event.waitUntil(self.registration.showNotification(title, {
    body: data.body || 'Há um novo aviso no painel.',
    icon: '/icon-192.png',
    badge: '/icon-192.png',
    tag: data.tag || 'optics',
    renotify: true,
    data: { url: data.url || '/' },
  }));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const target = new URL(event.notification.data && event.notification.data.url || '/', self.location.origin).href;
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
    for (const client of list) {
      if (client.url.startsWith(self.location.origin)) { client.focus(); return client.navigate(target).catch(() => undefined); }
    }
    return self.clients.openWindow(target);
  }));
});
