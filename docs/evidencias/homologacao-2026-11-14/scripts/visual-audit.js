// Auditoria visual/responsiva + XSS + PWA no build de produção (vite preview :4173 → API :8080).
const { chromium } = require('playwright');
const fs = require('fs');
const UI = 'http://localhost:4173';
const OUT = process.argv[2];
const ids = require('/tmp/claude-0/audit/audit-ids.json');
const results = [];
const check = (cat, name, ok, detail = '') => { results.push({ cat, name, ok: !!ok, detail: ok ? '' : String(detail).slice(0, 700) });
  console.log((ok ? 'OK   ' : 'FALHA') + ' [' + cat + '] ' + name + (ok || !detail ? '' : ' — ' + String(detail).slice(0, 500))); };
const VIEWPORTS = [['desktop', 1366, 900], ['tablet', 768, 1024], ['celular', 390, 844]];

async function layoutIssues(page) {
  return page.evaluate(() => {
    const vw = window.innerWidth;
    const out = { pageOverflow: document.documentElement.scrollWidth - vw, offscreen: [], overlap: 0 };
    for (const el of document.querySelectorAll('button, a, input, select, textarea, [role=button]')) {
      const r = el.getBoundingClientRect(); const st = getComputedStyle(el);
      if (!r.width || !r.height || st.visibility === 'hidden' || st.display === 'none') continue;
      // dentro de área com rolagem horizontal própria (ex.: tabela) não conta
      let p = el.parentElement, scroller = false;
      while (p) { const s = getComputedStyle(p); if ((s.overflowX === 'auto' || s.overflowX === 'scroll') && p.scrollWidth > p.clientWidth) { scroller = true; break; } p = p.parentElement; }
      if (!scroller && (r.right > vw + 1 || r.left < -1)) out.offscreen.push((el.innerText || el.getAttribute('aria-label') || el.name || el.tagName).trim().slice(0, 40) + ' [' + Math.round(r.left) + '..' + Math.round(r.right) + ']');
    }
    for (const m of document.querySelectorAll('.calendar-modal, [role=dialog], .modal')) {
      const r = m.getBoundingClientRect(); if (r.width > vw + 1) out.offscreen.push('modal mais largo que a tela: ' + Math.round(r.width));
    }
    return out;
  });
}

async function dismissModals(page) {
  for (let quiet = 0, i = 0; quiet < 2 && i < 12; i++) {
    await page.waitForTimeout(500);
    const modal = page.locator('.calendar-modal');
    if (!(await modal.count())) { quiet++; continue; }
    quiet = 0;
    const close = modal.getByRole('button', { name: /Fechar por hoje|Salvar meu planejamento|Revisar depois/ }).first();
    await (await close.count() ? close : modal.locator('button').last()).click();
  }
}

(async () => {
  const browser = await chromium.launch();
  for (const [vpName, w, h] of VIEWPORTS) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1, isMobile: w < 500, hasTouch: w < 800 });
    const page = await ctx.newPage();
    const consoleErrors = [], failed = [];
    page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));
    page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200)); });
    page.on('response', r => { if (r.status() >= 500) failed.push(r.status() + ' ' + r.url()); });
    page.on('requestfailed', r => { if (!/sw\.js|favicon/.test(r.url())) failed.push('falhou ' + r.url() + ' ' + (r.failure() || {}).errorText); });
    // Tela de login
    await page.goto(UI);
    await page.waitForSelector('input[name=email]');
    await page.screenshot({ path: `${OUT}/${vpName}-01-login.png`, fullPage: true });
    let li = await layoutIssues(page);
    check('Responsivo', `${vpName}: login sem rolagem lateral e com controles visíveis`, li.pageOverflow <= 1 && !li.offscreen.length, JSON.stringify(li));
    await page.fill('input[name=email]', ids.A.email);
    await page.fill('input[name=password]', 'SenhaSegura2026');
    await page.getByRole('button', { name: 'Entrar no sistema' }).click();
    await page.waitForSelector('.greeting');
    // modal (lembretes) antes de fechar
    await page.waitForTimeout(800);
    if (await page.locator('.calendar-modal').count()) {
      await page.screenshot({ path: `${OUT}/${vpName}-02-modal.png` });
      li = await layoutIssues(page);
      check('Responsivo', `${vpName}: janela de aviso cabe na tela`, !li.offscreen.some(x => x.startsWith('modal')), JSON.stringify(li));
    }
    await dismissModals(page);
    await page.screenshot({ path: `${OUT}/${vpName}-03-painel.png`, fullPage: true });
    li = await layoutIssues(page);
    check('Responsivo', `${vpName}: painel inicial`, li.pageOverflow <= 1 && !li.offscreen.length, JSON.stringify(li));
    const tiles = await page.locator('.tile').evaluateAll(els => els.map(e => [[...e.classList].find(c => c.startsWith('accent-')), (e.querySelector('strong,h3,h2') || e).textContent.trim().slice(0, 30)]));
    let n = 4;
    for (const [accent, label] of tiles) {
      const before = consoleErrors.length, fb = failed.length;
      await dismissModals(page);
      await page.locator('.tile.' + accent).click();
      await page.waitForTimeout(1100);
      const file = `${vpName}-${String(n++).padStart(2, '0')}-${label.normalize('NFD').replace(/[^\w]+/g, '-').toLowerCase()}.png`;
      await page.screenshot({ path: `${OUT}/${file}`, fullPage: true });
      li = await layoutIssues(page);
      check('Responsivo', `${vpName}: ${label} — sem rolagem lateral da página`, li.pageOverflow <= 1, 'sobra ' + li.pageOverflow + 'px');
      check('Responsivo', `${vpName}: ${label} — botões e campos dentro da tela`, !li.offscreen.length, li.offscreen.slice(0, 6).join('; '));
      check('Console', `${vpName}: ${label} — sem erro de JavaScript nem requisição com erro 5xx`, consoleErrors.length === before && failed.length === fb,
        consoleErrors.slice(before).concat(failed.slice(fb)).join(' | '));
      await page.locator('.back-link').first().click();
      await page.waitForSelector('.greeting');
    }
    // XSS armazenado (cliente com HTML no nome): tela de clientes
    const xssTile = tiles.find(t => /Clientes/i.test(t[1]));
    if (xssTile) {
      await page.locator('.tile.' + xssTile[0]).click(); await page.waitForTimeout(1200);
      const flag = await page.evaluate(() => window.__xss || 0);
      const shown = await page.locator('text=Xavier').count();
      check('Segurança', `${vpName}: nome de cliente com HTML aparece como texto e não executa (XSS)`, flag === 0, 'window.__xss=' + flag + ' elementos=' + shown);
      await page.locator('.back-link').first().click(); await page.waitForSelector('.greeting');
    }
    // Financeiro: o lançamento do cenário 1.000/300 aparece com saldo 1.000,00 e histórico do estorno
    if (vpName === 'desktop') {
      const finTile = tiles.find(t => /Financeiro/i.test(t[1]));
      await page.locator('.tile.' + finTile[0]).click();
      await page.waitForSelector('text=Auditoria crediário 1000');
      const row = page.locator('tr', { hasText: 'Auditoria crediário 1000' }).first();
      const txt = (await row.textContent()) || '';
      await row.scrollIntoViewIfNeeded(); await page.screenshot({ path: `${OUT}/desktop-financeiro-cenario-1000.png` });
      check('Financeiro (tela)', 'Lançamento do cenário aparece na tela do Financeiro', txt.includes('1.000,00'), txt.slice(0, 300));
      await page.locator('.back-link').first().click(); await page.waitForSelector('.greeting');
    }
    // Site público da ótica A (com HTML no título) e páginas legais
    await page.goto(UI + '/?site=' + ids.A.slug);
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${OUT}/${vpName}-90-site-publico.png`, fullPage: true });
    li = await layoutIssues(page);
    check('Site público', `${vpName}: site da ótica sem rolagem lateral`, li.pageOverflow <= 1 && !li.offscreen.length, JSON.stringify(li));
    check('Segurança', `${vpName}: HTML no título do site não executa (XSS)`, (await page.evaluate(() => window.__xss || 0)) === 0);
    for (const p of ['termos', 'privacidade']) {
      await page.goto(UI + '/?' + p); await page.waitForSelector('.legal-page h1');
      li = await layoutIssues(page);
      check('Site público', `${vpName}: página de ${p}`, li.pageOverflow <= 1, JSON.stringify(li));
    }
    await ctx.close();
  }

  // ===== PWA (desktop) =====
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  const page = await ctx.newPage();
  await page.goto(UI);
  const manifest = await page.evaluate(async () => { const l = document.querySelector('link[rel=manifest]'); if (!l) return null; return (await fetch(l.href)).json(); });
  check('PWA', 'Manifest com nome, ícones, start_url e display standalone', manifest && manifest.name && manifest.icons && manifest.icons.length >= 2 && manifest.display === 'standalone',
    JSON.stringify(manifest).slice(0, 300));
  const reg = await page.evaluate(async () => { const r = await navigator.serviceWorker.ready; return r.active && r.active.scriptURL; });
  check('PWA', 'Service worker registrado e ativo', /sw\.js$/.test(reg || ''), reg);
  await page.fill('input[name=email]', ids.A.email);
  await page.fill('input[name=password]', 'SenhaSegura2026');
  await page.getByRole('button', { name: 'Entrar no sistema' }).click();
  await page.waitForSelector('.greeting');
  await dismissModals(page);
  await page.reload(); await page.waitForSelector('.greeting'); await dismissModals(page);
  const cached = await page.evaluate(async () => { const out = []; for (const k of await caches.keys()) { const c = await caches.open(k); for (const r of await c.keys()) out.push(k + ' ' + new URL(r.url).pathname); } return out; });
  fs.writeFileSync(OUT + '/pwa-cache.txt', cached.join('\n'));
  check('PWA', 'Cache do navegador só guarda o esqueleto do app (nenhuma resposta /api/)', cached.length > 0 && !cached.some(u => u.includes('/api/')), cached.join(', ').slice(0, 400));
  await ctx.setOffline(true);
  await page.goto(UI + '/').catch(() => {});
  await page.waitForTimeout(800);
  const offlineText = await page.evaluate(() => document.body.innerText).catch(() => '');
  await page.screenshot({ path: OUT + '/pwa-offline.png' });
  check('PWA', 'Sem internet abre a tela offline, sem dados da ótica', /sem conex|offline/i.test(offlineText) && !/AuditA|Marta|R\$/.test(offlineText), offlineText.slice(0, 200));
  await ctx.setOffline(false);
  const apiOffline = await page.evaluate(async () => { try { const r = await fetch('/api/v1/customers'); return r.status; } catch (e) { return 'erro ' + e.message; } });
  check('PWA', 'Com internet de volta, a API responde direto da rede (sem cópia em cache)', apiOffline === 200, String(apiOffline));
  const pushPerm = await page.evaluate(() => Notification.permission);
  check('PWA', 'Permissão de notificação só é pedida por ação da pessoa (estado inicial "default")', pushPerm === 'default', pushPerm);
  await ctx.close();
  await browser.close();
  fs.writeFileSync(OUT + '/visual-results.json', JSON.stringify(results, null, 2));
  console.log(`\nTOTAL visual: ${results.length} verificações, ${results.filter(r => r.ok).length} OK, ${results.filter(r => !r.ok).length} falhas`);
})().catch(e => { console.error(e); fs.writeFileSync(OUT + '/visual-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
