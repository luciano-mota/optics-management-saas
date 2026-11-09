/* Cache apenas do shell público. Dados pessoais e APIs privadas nunca ficam no cache. */
const CACHE='optics-public-static-v1';
self.addEventListener('install',event=>{
  event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(['/','/icon.svg'])));
  self.skipWaiting();
});
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('fetch',event=>{
  const url=new URL(event.request.url);
  if(event.request.method!=='GET'||url.origin!==self.location.origin||
    url.pathname.startsWith('/api/')||url.pathname.startsWith('/actuator/')||
    url.pathname==='/'||event.request.headers.has('authorization'))return;
  event.respondWith(fetch(event.request).catch(()=>caches.match(event.request)));
});
