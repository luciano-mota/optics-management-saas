const { chromium } = require('playwright');
const ids = require(process.env.IDS);
(async () => {
  const b = await chromium.launch(); const p = await (await b.newContext({ viewport: { width: 1366, height: 900 } })).newPage();
  const errs = []; p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  await p.goto(process.env.UI); await p.fill('input[name=email]', ids.A.email); await p.fill('input[name=password]', 'SenhaSegura2026');
  await p.getByRole('button', { name: 'Entrar no sistema' }).click(); await p.waitForSelector('.greeting');
  for (let i = 0; i < 8; i++) { await p.waitForTimeout(400); const m = p.locator('.calendar-modal'); if (!(await m.count())) break;
    const c = m.getByRole('button', { name: /Fechar por hoje|Salvar meu planejamento|Revisar depois/ }).first(); await (await c.count() ? c : m.locator('button').last()).click(); }
  await p.locator('.tile', { hasText: 'Vendas' }).first().click(); await p.waitForSelector('text=Consultar vendas e entregas');
  const btn = p.getByRole('button', { name: 'Cancelar venda' }).first(); await btn.scrollIntoViewIfNeeded(); await btn.click();
  await p.waitForSelector('.cancel-box'); const box = p.locator('.cancel-box').first();
  const txt = await box.textContent(); await box.locator('xpath=ancestor::tr').screenshot({ path: process.env.OUT + '/vendas-cancelar.png' });
  console.log(JSON.stringify({ texto: txt.slice(0, 160), errosConsole: errs }));
  await b.close();
})();
