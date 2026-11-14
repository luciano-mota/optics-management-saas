const { request } = require('playwright');
const crypto = require('crypto');
const ids = require('/tmp/claude-0/audit/audit-ids.json');
function totp(secret) { const al = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = ''; for (const c of secret.replace(/\s/g, '')) bits += al.indexOf(c).toString(2).padStart(5, '0');
  const bytes = []; for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  const msg = Buffer.alloc(8); msg.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000))); const h = crypto.createHmac('sha1', Buffer.from(bytes)).update(msg).digest();
  const o = h[h.length - 1] & 15; return String((((h[o] & 127) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]) % 1000000).padStart(6, '0'); }
async function client() { const c = await request.newContext({ baseURL: 'http://localhost:8080' }); const s = { c, t: null };
  s.csrf = async () => { s.t = (await (await c.get('/api/v1/auth/csrf')).json()).token; }; await s.csrf();
  s.post = async (p, d, form) => { const r = await c.post('/api/v1' + p, { headers: { 'X-XSRF-TOKEN': s.t }, ...(form ? { form: d } : { data: d }) }); return { status: r.status(), text: await r.text() }; };
  return s; }
(async () => {
  const b = await client();
  await b.post('/auth/login', { email: ids.seller, password: 'SenhaVendedora2026' }, true); await b.csrf();
  const setup = JSON.parse((await b.post('/auth/mfa/setup', {})).text);
  console.log('ativar MFA', (await b.post('/auth/mfa/enable', { code: totp(setup.secret) })).status);
  const x = await client();
  await x.post('/auth/login', { email: ids.seller, password: 'SenhaVendedora2026' }, true); await x.csrf();
  const codes = [];
  for (let i = 0; i < 10; i++) codes.push((await x.post('/auth/mfa/verify', { code: String(100000 + i) })).status);
  console.log('sessão 1 (10 tentativas):', codes.join(','));
  const y = await client(); await y.post('/auth/login', { email: ids.seller, password: 'SenhaVendedora2026' }, true); await y.csrf();
  const c2 = []; for (let i = 0; i < 5; i++) { const r = await y.post('/auth/mfa/verify', { code: String(200000 + i) }); c2.push(r.status + ':' + r.text.slice(0, 60)); }
  console.log('sessão 2:', c2.join(' | '));
  const z = await client(); await z.post('/auth/login', { email: ids.seller, password: 'SenhaVendedora2026' }, true); await z.csrf();
  const right = await z.post('/auth/mfa/verify', { code: totp(setup.secret) });
  console.log('sessão 3, código CERTO após 10 erros na conta:', right.status, right.text);
})();
