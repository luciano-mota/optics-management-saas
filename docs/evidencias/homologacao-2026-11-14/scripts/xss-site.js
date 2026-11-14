const { request, chromium } = require('playwright');
const ids = require('/tmp/claude-0/audit/audit-ids.json');
(async () => {
  const c = await request.newContext({ baseURL: 'http://localhost:8080' });
  let t = (await (await c.get('/api/v1/auth/csrf')).json()).token;
  await c.post('/api/v1/auth/login', { headers: { 'X-XSRF-TOKEN': t }, form: { email: ids.A.email, password: 'SenhaSegura2026' } });
  t = (await (await c.get('/api/v1/auth/csrf')).json()).token;
  const cur = await (await c.get('/api/v1/site-settings')).json();
  console.log('services exemplo', JSON.stringify(cur.services).slice(0,150), 'faqs', JSON.stringify(cur.faqs).slice(0,120));
  const p = '<img src=x onerror="window.__xss=1">';
  const r = await c.put('/api/v1/site-settings', { headers: { 'X-XSRF-TOKEN': t }, data: { primaryColor: '#7c3aed', secondaryColor: '#1e1b4b', template: 'acolhedor',
    mood: 'suave', fonts: 'suave', headline: p + 'Título', subtitle: '<script>window.__xss=2</script>Sub', about: p + 'Sobre', hours: p, instagram: '@x',
    showMap: true, services: cur.services, testimonials: [{ author: p, text: p }], faqs: (cur.faqs||[]).map(f => ({ ...f, question: p + (f.question||''), answer: p })),
    homeVisitsEnabled: true, homeVisitTitle: p + 'Visita', homeVisitDescription: p } });
  console.log('PUT site', r.status(), (await r.text()).slice(0, 120));
  const b = await chromium.launch(); const pg = await (await b.newContext()).newPage();
  const dialogs = []; pg.on('dialog', d => { dialogs.push(d.message()); d.dismiss(); });
  await pg.goto('http://localhost:4173/?site=' + ids.A.slug); await pg.waitForTimeout(2000);
  const flag = await pg.evaluate(() => window.__xss || 0);
  const shown = await pg.locator('text=Título').count();
  const imgs = await pg.evaluate(() => [...document.querySelectorAll('img')].filter(i => i.getAttribute('src') === 'x').length);
  await pg.screenshot({ path: '/tmp/claude-0/audit/shots/xss-site.png', fullPage: false });
  console.log(JSON.stringify({ xssExecutou: flag, dialogs, tituloVisivel: shown, imgsInjetadas: imgs, title: await pg.title() }));
  await b.close();
})();
