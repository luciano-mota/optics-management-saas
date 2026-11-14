// Cenários ADICIONAIS da auditoria de homologação (não fazem parte do repositório).
// Uso: node e2e-audit.js <fase> <pasta-de-saida>   fase = validacao | principal
// Ambiente: backend local isolado (banco optics_audit), sem e-mail, sem Asaas, rede externa bloqueada.
const { request } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const BASE = process.env.BASE || 'http://localhost:8080';
const PHASE = process.argv[2];
const OUT = process.argv[3] || '/tmp/claude-0/audit';
const results = [];
function check(cat, name, ok, detail = '') {
  results.push({ cat, name, ok: !!ok, detail: ok ? '' : String(detail).slice(0, 600) });
  console.log((ok ? 'OK   ' : 'FALHA') + ' [' + cat + '] ' + name + (ok || !detail ? '' : ' — ' + String(detail).slice(0, 400)));
}
function note(cat, name, detail) { results.push({ cat, name, ok: null, detail }); console.log('NOTA  [' + cat + '] ' + name + ' — ' + detail); }
function sql(q) {
  const out = execFileSync('psql', ['-h', 'localhost', '-U', 'optics', '-d', 'optics_audit', '-tA', '-F', '|', '-c', q],
    { env: { ...process.env, PGPASSWORD: 'local-dev-only' } }).toString().trim();
  return out ? out.split('\n').map(l => l.split('|')) : [];
}
function cpf() {
  const d = Array.from({ length: 9 }, () => Math.floor(Math.random() * 10));
  for (const len of [9, 10]) { let s = 0; for (let i = 0; i < len; i++) s += d[i] * (len + 1 - i); const c = 11 - (s % 11); d.push(c >= 10 ? 0 : c); }
  return d.join('');
}
const pdf = Buffer.from('%PDF-1.4\n% receita de teste\n').toString('base64');
const localToday = () => sql("select (now() at time zone 'America/Sao_Paulo')::date")[0][0];

class Client {
  constructor(name) { this.name = name; }
  async init() { this.ctx = await request.newContext({ baseURL: BASE }); await this.csrf(); return this; }
  async csrf() { const r = await this.ctx.get('/api/v1/auth/csrf'); this.token = (await r.json()).token; }
  async call(method, path, data, opts = {}) {
    const headers = method === 'GET' || opts.noCsrf ? {} : { 'X-XSRF-TOKEN': this.token };
    const o = { headers };
    if (data !== undefined) { if (opts.form) o.form = data; else if (opts.raw) { o.data = data; headers['Content-Type'] = 'application/json'; } else o.data = data; }
    const r = await this.ctx.fetch((opts.abs ? '' : '/api/v1') + path, { method, ...o });
    let body = null; const type = r.headers()['content-type'] || '';
    const buf = await r.body();
    if (type.includes('json')) { try { body = JSON.parse(buf.toString()); } catch { body = buf.toString(); } } else body = buf;
    return { status: r.status(), body, type, headers: r.headers(), text: buf.toString() };
  }
  async login(email, password) {
    await this.csrf();
    const r = await this.call('POST', '/auth/login', { email, password }, { form: true });
    await this.csrf();
    return r;
  }
}

async function signupOptic(label) {
  const c = await new Client(label).init();
  const email = 'audit-' + label.toLowerCase() + Date.now() + '@e2e.test';
  const s = await c.call('POST', '/public/signup', { opticName: 'Ótica ' + label, fullName: 'Dona ' + label, email,
    password: 'SenhaSegura2026', whatsapp: '98999990000', acceptedTerms: true });
  check('Cadastro', label + ': cadastro público', s.status === 200, 'HTTP ' + s.status + ' ' + JSON.stringify(s.body));
  check('Cadastro', label + ': login', (await c.login(email, 'SenhaSegura2026')).status === 200);
  const p = await c.call('PUT', '/auth/profile', { fullName: 'Dona ' + label, cpf: cpf(), phone: '98999990000',
    birthDate: '1988-04-12', address: null, opticCnpj: '11.222.333/0001-81',
    opticAddress: { address: 'Rua Grande, 100 - Centro, São Luís/MA - CEP 65020-250', city: 'São Luís', uf: 'MA', ibge: '2111300' } });
  check('Cadastro', label + ': conclui primeiro acesso', p.status === 200, JSON.stringify(p.body));
  c.me = (await c.call('GET', '/auth/me')).body; c.email = email; c.opticId = c.me.activeOpticId; c.accountId = c.me.account.id;
  return c;
}

async function validationPhase() {
  // 5 pedidos de cadastro inválidos = limite por IP (5/hora). O backend é reiniciado antes da fase principal.
  const anon = await new Client('anon').init();
  const existing = sql("select email from accounts where email like 'alfa%@e2e.test' order by created_at limit 1")[0][0];
  const before = Number(sql('select count(*) from accounts')[0][0]);
  const base = { opticName: 'Ótica Dup', fullName: 'Dona Dup', email: existing, password: 'SenhaSegura2026', whatsapp: '98999990000', acceptedTerms: true };
  const dup = await anon.call('POST', '/public/signup', base);
  check('Cadastro', 'E-mail já cadastrado é recusado', dup.status >= 400 && dup.status < 500, 'HTTP ' + dup.status + ' ' + JSON.stringify(dup.body));
  const bad = await anon.call('POST', '/public/signup', { ...base, email: 'nao-e-um-email' });
  check('Cadastro', 'E-mail inválido recusado', bad.status === 400, 'HTTP ' + bad.status + ' ' + JSON.stringify(bad.body));
  const weak = await anon.call('POST', '/public/signup', { ...base, email: 'fraca' + Date.now() + '@e2e.test', password: '123' });
  check('Cadastro', 'Senha fraca recusada', weak.status === 400, 'HTTP ' + weak.status + ' ' + JSON.stringify(weak.body));
  const noName = await anon.call('POST', '/public/signup', { ...base, email: 'semnome' + Date.now() + '@e2e.test', opticName: '' });
  check('Cadastro', 'Campo obrigatório (nome da ótica) recusado', noName.status === 400, 'HTTP ' + noName.status + ' ' + JSON.stringify(noName.body));
  const noTerms = await anon.call('POST', '/public/signup', { ...base, email: 'semtermos' + Date.now() + '@e2e.test', acceptedTerms: false });
  check('Cadastro', 'Sem aceite dos termos recusado', noTerms.status === 400, 'HTTP ' + noTerms.status + ' ' + JSON.stringify(noTerms.body));
  check('Cadastro', 'Nenhuma conta criada pelos pedidos inválidos', Number(sql('select count(*) from accounts')[0][0]) === before);
  const sixth = await anon.call('POST', '/public/signup', { ...base, email: 'sexto' + Date.now() + '@e2e.test' });
  check('Segurança', 'Limite de cadastros por IP (6º pedido na hora = 429)', sixth.status === 429, 'HTTP ' + sixth.status);
}

async function mainPhase() {
  // ================= 6.1 Operador e sessão =================
  const op = await new Client('op').init();
  const wrong = await op.login('operador@lcm.test', 'SenhaErrada2026x');
  const unknown = await op.login('ninguem-' + Date.now() + '@e2e.test', 'SenhaErrada2026x');
  check('Autenticação', 'Senha errada = 401', wrong.status === 401, 'HTTP ' + wrong.status);
  check('Autenticação', 'E-mail inexistente = 401 (mesma resposta, sem revelar conta)', unknown.status === 401 && unknown.text.replace(/"timestamp":"[^"]*"/,'') === wrong.text.replace(/"timestamp":"[^"]*"/,''), unknown.text + ' x ' + wrong.text);
  const json = await op.call('POST', '/auth/login', { email: 'operador@lcm.test', password: 'SenhaForte2026x' });
  check('Autenticação', 'Login com corpo JSON recusado com mensagem (400)', json.status === 400, 'HTTP ' + json.status);
  const noCsrf = await op.call('POST', '/auth/login', { email: 'operador@lcm.test', password: 'SenhaForte2026x' }, { form: true, noCsrf: true });
  check('Segurança', 'Login sem token CSRF = 403', noCsrf.status === 403, 'HTTP ' + noCsrf.status);
  const pre = (await op.ctx.storageState()).cookies.find(c => c.name === 'SESSION');
  const ok = await op.login('operador@lcm.test', 'SenhaForte2026x');
  check('Autenticação', 'Login do operador (conta das variáveis PLATFORM_ADMIN_*)', ok.status === 200, 'HTTP ' + ok.status);
  const cookies = (await op.ctx.storageState()).cookies;
  const sess = cookies.find(c => c.name === 'SESSION');
  check('Segurança', 'Cookie de sessão HttpOnly e SameSite=Lax', sess && sess.httpOnly && sess.sameSite === 'Lax', JSON.stringify(sess));
  check('Segurança', 'ID da sessão muda no login (anti fixação de sessão)', !pre || (sess && pre.value !== sess.value), 'antes=' + (pre && pre.value.slice(0, 6)));
  note('Segurança', 'Cookie Secure', 'Local roda em HTTP com COOKIE_SECURE=false; no Render o atributo Secure depende da variável COOKIE_SECURE (não verificável sem login no hml).');
  const opMe = (await op.call('GET', '/auth/me')).body;
  check('Autenticação', 'Operador é operador da plataforma', opMe && opMe.platformOperator === true);
  check('Segurança', '/auth/me não expõe hash de senha nem segredo MFA', !/password|mfa_secret|\$2a\$/.test(JSON.stringify(opMe)), JSON.stringify(opMe).slice(0, 200));
  check('Autenticação', 'Painel do operador (lista de óticas)', (await op.call('GET', '/platform/optics')).status === 200);
  const out = await op.call('POST', '/auth/logout', {});
  check('Autenticação', 'Logout = 204', out.status === 204, 'HTTP ' + out.status);
  check('Autenticação', 'Depois do logout a API recusa (401)', (await op.call('GET', '/auth/me')).status === 401);
  // Expiração: a sessão inativa por mais de 45 min deixa de valer (simulado recuando last_access_time no banco de teste).
  await op.login('operador@lcm.test', 'SenhaForte2026x');
  check('Autenticação', 'Sessão nova válida antes de expirar', (await op.call('GET', '/auth/me')).status === 200);
  sql("update spring_session set last_access_time = last_access_time - 46*60*1000, expiry_time = expiry_time - 46*60*1000 where principal_name='operador@lcm.test'");
  check('Autenticação', 'Sessão inativa por mais de 45 min expira (401)', (await op.call('GET', '/auth/me')).status === 401);
  await op.login('operador@lcm.test', 'SenhaForte2026x');
  // Força bruta: 12 erros na mesma conta bloqueiam a 13ª tentativa.
  const victim = 'alvo-' + Date.now() + '@e2e.test';
  const brute = await new Client('brute').init();
  let last;
  for (let i = 0; i < 13; i++) last = await brute.login(victim, 'Errada' + i);
  check('Segurança', 'Limite de tentativas de login (13ª = 429 com Retry-After)', last.status === 429 && !!last.headers['retry-after'], 'HTTP ' + last.status);

  // ================= 6.2 Cadastro e primeiro acesso =================
  const A = await signupOptic('AuditA');
  const B = await signupOptic('AuditB');
  const subA = sql(`select s.billing_mode, (s.trial_ends_at - now()) > interval '6 days', o.site_enabled, o.home_visits_enabled, m.role, m.primary_master
     from optic_subscriptions s join optics o on o.id=s.optic_id join optic_memberships m on m.optic_id=o.id where o.id='${A.opticId}'`)[0];
  check('Cadastro', 'Nova ótica: teste de 7 dias, site fechado, visita em domicílio desligada, dona = MASTER principal',
    subA[0] === 'TRIAL' && subA[1] === 't' && subA[2] === 'f' && subA[3] === 'f' && subA[4] === 'MASTER' && subA[5] === 't', JSON.stringify(subA));
  check('Cadastro', 'Ótica A e B são tenants diferentes', A.opticId && B.opticId && A.opticId !== B.opticId);
  const pwHash = sql(`select password_hash from accounts where email='${A.email}'`)[0][0];
  check('Segurança', 'Senha guardada com BCrypt custo 12', /^\$2[aby]\$12\$/.test(pwHash), pwHash.slice(0, 7));

  // ================= 6.4 Clientes =================
  const cpfC = cpf();
  const c1 = await A.call('POST', '/customers', { fullName: 'Marta Auditoria', cpf: cpfC, birthDate: '1980-01-15', phone: '(98) 98111-2222', address: 'Rua A, 1 - Centro, São Luís/MA' });
  check('Clientes', 'Cadastro de cliente', c1.status === 200, JSON.stringify(c1.body));
  const ed = await A.call('PUT', '/customers/' + c1.body.id, { fullName: 'Marta Auditoria Silva', cpf: cpfC, birthDate: '1980-01-15', phone: '(98) 98111-2222', address: 'Rua A, 2 - Centro, São Luís/MA' });
  check('Clientes', 'Edição de cliente', ed.status === 200 && sql(`select full_name from customers where id='${c1.body.id}'`)[0][0] === 'Marta Auditoria Silva', JSON.stringify(ed.body));
  const byName = (await A.call('GET', '/customers?q=auditoria')).body;
  const byCpf = (await A.call('GET', '/customers?q=' + cpfC.slice(0, 5))).body;
  check('Clientes', 'Busca por nome (sem diferenciar maiúsculas) e por CPF', byName.length === 1 && byCpf.length === 1, JSON.stringify([byName.length, byCpf.length]));
  const phoneDigits = sql(`select phone from customers where id='${c1.body.id}'`)[0][0];
  note('Clientes', 'Telefone guardado como digitado', 'valor no banco: "' + phoneDigits + '" (não normalizado para só dígitos)');
  const badCpf = await A.call('POST', '/customers', { fullName: 'CPF Repetido', cpf: '111.111.111-11', birthDate: '1980-01-15', phone: '98', address: 'Rua' });
  check('Clientes', 'CPF inválido (dígitos verificadores) recusado', badCpf.status === 400, 'HTTP ' + badCpf.status + ' ' + JSON.stringify(badCpf.body));
  const dupCpf = await A.call('POST', '/customers', { fullName: 'Outra Pessoa', cpf: cpfC, birthDate: '1980-01-15', phone: '98', address: 'Rua' });
  check('Clientes', 'CPF duplicado na mesma ótica recusado', dupCpf.status >= 400 && dupCpf.status < 500, 'HTTP ' + dupCpf.status + ' ' + JSON.stringify(dupCpf.body));
  const future = await A.call('POST', '/customers', { fullName: 'Nascida no Futuro', cpf: cpf(), birthDate: '2099-01-01', phone: '98', address: 'Rua' });
  check('Clientes', 'Data de nascimento futura recusada', future.status === 400, 'HTTP ' + future.status + ' ' + JSON.stringify(future.body));
  const missing = await A.call('POST', '/customers', { fullName: '', cpf: cpf(), birthDate: '1980-01-01', address: 'Rua' });
  check('Clientes', 'Nome obrigatório', missing.status === 400);
  const del = await A.call('DELETE', '/customers/' + c1.body.id);
  note('Clientes', 'Desativar/excluir cliente', 'DELETE /customers/{id} → HTTP ' + del.status + '; não há endpoint de desativação nem de anonimização (LGPD) de cliente.');
  const sqli = await A.call('GET', "/customers?q=" + encodeURIComponent("' OR '1'='1"));
  check('Segurança', 'SQL injection na busca de clientes não traz dados nem erro 500', sqli.status === 200 && sqli.body.length === 0, 'HTTP ' + sqli.status + ' ' + JSON.stringify(sqli.body).slice(0, 100));
  const xssName = '<img src=x onerror="window.__xss=1">Xavier';
  const cx = await A.call('POST', '/customers', { fullName: xssName, cpf: cpf(), birthDate: '1975-03-03', phone: '98977770000', address: 'Rua <script>window.__xss=2</script>' });
  check('Segurança', 'Texto com HTML é guardado como texto (escapado na tela; ver teste visual)', cx.status === 200);
  // Anexos
  const exe = Buffer.from('MZ\x90\x00 programa').toString('base64');
  const fake = await A.call('POST', '/attachments/customers/' + c1.body.id, { filename: 'receita.pdf', mime: 'application/pdf', dataBase64: exe });
  check('Clientes', 'Anexo com conteúdo que não é PDF recusado (assinatura do arquivo)', fake.status === 400, 'HTTP ' + fake.status);
  const html = await A.call('POST', '/attachments/customers/' + c1.body.id, { filename: 'x.html', mime: 'text/html', dataBase64: Buffer.from('<script>alert(1)</script>').toString('base64') });
  check('Clientes', 'Anexo HTML recusado', html.status === 400, 'HTTP ' + html.status);
  const big = Buffer.concat([Buffer.from('%PDF-1.4\n'), Buffer.alloc(4 * 1024 * 1024 + 10, 32)]).toString('base64');
  const bigR = await A.call('POST', '/attachments/customers/' + c1.body.id, { filename: 'grande.pdf', mime: 'application/pdf', dataBase64: big });
  check('Clientes', 'Anexo acima de 4 MB recusado', bigR.status === 400 || bigR.status === 413, 'HTTP ' + bigR.status);
  const goodAtt = await A.call('POST', '/attachments/customers/' + c1.body.id, { filename: 'receita.pdf', mime: 'application/pdf', dataBase64: pdf });
  check('Clientes', 'Anexo PDF válido aceito', goodAtt.status === 200, JSON.stringify(goodAtt.body));

  // ================= 6.5 Catálogo e estoque =================
  const lens = (await A.call('POST', '/catalog', { kind: 'LENS', brand: 'Zeiss', model: 'SmartLife', category: 'MONOFOCAL' })).body;
  const frame = (await A.call('POST', '/catalog', { kind: 'FRAME', brand: 'Oakley', model: 'OX8156', category: 'GRAU' })).body;
  check('Catálogo', 'Cadastro de lente e armação', lens.id && frame.id, JSON.stringify([lens, frame]));
  check('Catálogo', 'Edição de produto', (await A.call('PATCH', '/catalog/' + frame.id, { brand: 'Oakley', model: 'OX8156 Preto', category: 'GRAU' })).status === 200);
  const fr = (await A.call('GET', '/catalog?kind=FRAME')).body;
  check('Catálogo', 'Lista por tipo (sem busca por texto no endpoint)', fr.length === 1 && fr[0].model === 'OX8156 Preto', JSON.stringify(fr));
  note('Catálogo', 'Preço no catálogo', 'O cadastro de produto não tem preço (só marca, modelo e categoria); o valor é informado em cada venda.');
  const sup = (await A.call('POST', '/suppliers', { name: 'Fornecedor Auditoria', kind: 'FORNECEDOR', phone: '9833330000' })).body;
  check('Estoque', 'Cadastro de fornecedor', !!sup.id, JSON.stringify(sup));
  const supEdit = await A.call('PUT', '/suppliers/' + sup.id, { name: 'Fornecedor Auditoria LTDA', kind: 'FORNECEDOR', phone: '9833330000' });
  check('Estoque', 'Edição de fornecedor', supEdit.status === 200, 'HTTP ' + supEdit.status + ' ' + JSON.stringify(supEdit.body));
  check('Estoque', 'Controlar estoque da armação', (await A.call('PATCH', '/stock/items/' + frame.id, { trackStock: true, minStock: 1 })).status === 200);
  const e1 = await A.call('POST', '/stock/movements', { itemId: frame.id, kind: 'ENTRADA', quantity: 3, unitCost: 120.5, supplierId: sup.id });
  check('Estoque', 'Entrada de 3 unidades', e1.status === 200 && e1.body.quantity === 3, JSON.stringify(e1.body));
  const neg = await A.call('POST', '/stock/movements', { itemId: frame.id, kind: 'AJUSTE', quantity: -10, note: 'quebra' });
  check('Estoque', 'Ajuste que deixaria o estoque negativo é recusado', neg.status === 400, 'HTTP ' + neg.status + ' ' + JSON.stringify(neg.body));
  const zero = await A.call('POST', '/stock/movements', { itemId: frame.id, kind: 'ENTRADA', quantity: 0 });
  check('Estoque', 'Entrada com quantidade zero recusada', zero.status === 400, 'HTTP ' + zero.status);
  const adj = await A.call('POST', '/stock/movements', { itemId: frame.id, kind: 'AJUSTE', quantity: -1, note: 'quebra na vitrine' });
  check('Estoque', 'Ajuste de -1 (quebra)', adj.status === 200 && adj.body.quantity === 2, JSON.stringify(adj.body));

  // ================= 6.6 Vendas =================
  const mach = (await A.call('POST', '/machines', { name: 'Maquininha Auditoria', debitFee: 1.5, creditFee: 3.0, fixedFee: 0, debitDays: 1, creditDays: 30,
    anticipated: false, installmentFees: [{ installments: 2, saleFee: 4.0, installmentFee: 0 }], creditTerms: [{ days: 30, fee: 3.0 }] })).body;
  const sale = (method, amount, extra = {}) => A.call('POST', '/sales', { customerId: c1.body.id, lensId: lens.id, frameId: frame.id, amount,
    paymentMethod: method, cardMachineId: method === 'PIX' || method === 'DINHEIRO' ? null : mach.id, installments: 1, passFee: false,
    settlementDays: null, replacementMonths: 12, expectedDelivery: null, notes: '', idempotencyKey: 'audit-' + Math.random(), ...extra });
  const s0 = await sale('PIX', 0); check('Vendas', 'Venda de valor zero recusada', s0.status === 400);
  const sn = await sale('PIX', -5); check('Vendas', 'Venda de valor negativo recusada', sn.status === 400);
  const s3 = await sale('PIX', 100.005);
  const stored = s3.status === 200 ? sql(`select gross_amount from sales where id='${s3.body.id}'`)[0][0] : 'HTTP ' + s3.status;
  note('Vendas', 'Valor com 3 casas decimais (100,005)', 'HTTP ' + s3.status + '; guardado como ' + stored);
  const idem = 'audit-idem-' + Date.now();
  const i1 = await sale('PIX', 250, { idempotencyKey: idem }), i2 = await sale('PIX', 250, { idempotencyKey: idem });
  check('Vendas', 'Clique duplo não duplica a venda (idempotência)', i1.body.id === i2.body.id && i2.body.alreadyCreated === true, JSON.stringify([i1.body, i2.body]));
  note('Vendas', 'Vários produtos, quantidade e desconto', 'A venda tem exatamente 1 lente + 1 armação e um valor total; não há itens múltiplos, quantidade nem campo de desconto (regra atual do produto).');
  // Venda Pix de R$ 1.000 (o cenário do roteiro)
  const stockBefore = Number(sql(`select coalesce(sum(quantity),0) from stock_movements where item_id='${frame.id}'`)[0][0]);
  const pix = await sale('PIX', 1000);
  check('Vendas', 'Venda Pix de R$ 1.000,00', pix.status === 200, JSON.stringify(pix.body));
  const pixEntry = sql(`select id, net_amount, settled_on is not null from finance_entries where sale_id='${pix.body.id}'`)[0];
  check('Financeiro', 'Venda Pix gera lançamento já recebido de R$ 1.000,00', pixEntry && pixEntry[1] === '1000.00' && pixEntry[2] === 't', JSON.stringify(pixEntry));
  const pixSettl = sql(`select count(*), sum(amount) from finance_settlements where entry_id='${pixEntry[0]}'`)[0];
  check('Financeiro', 'Venda Pix tem exatamente 1 baixa de R$ 1.000,00 (sem duplicidade)', pixSettl[0] === '1' && pixSettl[1] === '1000.00', JSON.stringify(pixSettl));
  const partialOnSale = await A.call('POST', '/finance/' + pixEntry[0] + '/settlements', { amount: 300 });
  check('Financeiro', 'Venda Pix não aceita baixa parcial (já quitada)', partialOnSale.status === 400, JSON.stringify(partialOnSale.body));
  const card = await sale('CREDITO', 1000);
  const cardEntry = sql(`select id, net_amount, fee_amount from finance_entries where sale_id='${card.body.id}'`)[0];
  const partialCard = await A.call('POST', '/finance/' + cardEntry[0] + '/settlements', { amount: 300 });
  check('Financeiro', 'Recebível de cartão recusa baixa parcial (regra: só a parcela inteira)', partialCard.status === 400, JSON.stringify(partialCard.body));
  note('Financeiro', 'Cenário "venda de R$ 1.000 com pagamento parcial de R$ 300"', 'Não é representável numa VENDA: venda aceita só Dinheiro/Pix (quitada na hora) ou Débito/Crédito (recebível de cartão, baixa integral). Não existe forma de pagamento crediário/fiado na venda. O pagamento parcial existe só em lançamento manual do Financeiro, sem vínculo com venda ou cliente.');
  const stockAfter = Number(sql(`select coalesce(sum(quantity),0) from stock_movements where item_id='${frame.id}'`)[0][0]);
  check('Estoque', 'Cada venda baixa 1 armação do estoque', stockAfter === stockBefore - 2, stockBefore + ' -> ' + stockAfter);

  // Cancelamento de venda já recebida: o que acontece com o dinheiro e com o histórico
  const today = localToday();
  const sumBefore = (await A.call('GET', '/finance/summary')).body;
  const cancel = await A.call('PATCH', '/sales/' + pix.body.id + '/status', { status: 'CANCELLED' });
  check('Vendas', 'Cancelar venda', cancel.status === 200, JSON.stringify(cancel.body));
  const sumAfter = (await A.call('GET', '/finance/summary')).body;
  const afterCancel = sql(`select e.reversed_at is not null, (select count(*) from finance_settlements st where st.entry_id=e.id and st.reversed_at is null),
     (select count(*) from finance_entries x where x.sale_id=e.sale_id and x.direction='EXPENSE') from finance_entries e where e.id='${pixEntry[0]}'`)[0];
  check('Vendas', 'Cancelamento devolve a armação ao estoque', Number(sql(`select coalesce(sum(quantity),0) from stock_movements where item_id='${frame.id}'`)[0][0]) === stockAfter + 1);
  note('Financeiro', 'Cancelar venda Pix já recebida', `lançamento marcado como estornado=${afterCancel[0]}; baixas ainda ativas=${afterCancel[1]}; lançamento de devolução ao cliente=${afterCancel[2]}; "recebido no mês" ${sumBefore.received_month} -> ${sumAfter.received_month}`);
  check('Financeiro', 'Cancelar venda paga registra a devolução (estorno da baixa com data) em vez de sumir com o recebido',
    afterCancel[1] === '0' || afterCancel[2] !== '0', 'baixa continua ativa e não há registro de devolução; o caixa do mês caiu ' + (Number(sumBefore.received_month) - Number(sumAfter.received_month)).toFixed(2));
  // Fotografia histórica: venda com data passada, consultada "na data" antes e depois do cancelamento.
  const past = sql(`select ((now() at time zone 'America/Sao_Paulo')::date - 2)::text, ((now() at time zone 'America/Sao_Paulo')::date - 1)::text`)[0];
  const ym = { y: Number(past[0].slice(0, 4)), m: Number(past[0].slice(5, 7)) };
  const old = await sale('PIX', 777, { soldOn: past[0] });
  if (old.status === 200 && Number(past[1].slice(5, 7)) === ym.m) {
    const q = `/reports/monthly-sales-summary?year=${ym.y}&month=${ym.m}&asOf=${past[1]}`;
    const snap1 = (await A.call('GET', q)).body.summary;
    await A.call('PATCH', '/sales/' + old.body.id + '/status', { status: 'CANCELLED' });
    const snap2 = (await A.call('GET', q)).body.summary;
    check('Relatórios', 'Cancelar hoje não altera a fotografia de "Vendas Atualizadas" de ontem',
      JSON.stringify(snap1) === JSON.stringify(snap2), 'antes=' + JSON.stringify(snap1).slice(0, 300) + ' depois=' + JSON.stringify(snap2).slice(0, 300));
  } else note('Relatórios', 'Fotografia histórica', 'não executado: venda com data passada HTTP ' + old.status + ' ou virada de mês');

  // ================= 6.7 Financeiro — cenário obrigatório R$ 1.000 / R$ 300 =================
  const recBefore = Number((await A.call('GET', '/finance/summary')).body.receivable);
  const entry = (await A.call('POST', '/finance', { direction: 'INCOME', category: 'Crediário', description: 'Auditoria crediário 1000',
    amount: 1000, occurredOn: null, dueOn: null, settled: false })).body;
  const row = async () => ((await A.call('GET', '/finance')).body || []).find(e => e.id === entry.id);
  let r = await row();
  check('Financeiro', '1. Conta a receber de R$ 1.000,00 (aberto 1.000,00)', r && Number(r.open_amount) === 1000 && Number(r.paid_amount) === 0, JSON.stringify(r));
  const p300 = await A.call('POST', '/finance/' + entry.id + '/settlements', { amount: 300, paidOn: null, note: 'Entrada' });
  r = await row();
  check('Financeiro', '2-3. Recebe R$ 300,00 → saldo R$ 700,00 (API e banco)', p300.status === 200 && Number(r.open_amount) === 700 && Number(r.paid_amount) === 300
    && sql(`select sum(amount) from finance_settlements where entry_id='${entry.id}' and reversed_at is null`)[0][0] === '300.00', JSON.stringify([p300.body, r]));
  check('Financeiro', 'Resumo "a receber" sobe 700,00', Math.abs(Number((await A.call('GET', '/finance/summary')).body.receivable) - recBefore - 700) < 0.005);
  const rv = await A.call('POST', '/finance/settlements/' + p300.body.settlementId + '/reverse', { reason: 'Cliente desistiu' });
  r = await row();
  check('Financeiro', '4-5. Estorno do R$ 300,00 → recebido ativo R$ 0,00', rv.status === 200 && Number(r.paid_amount) === 0, JSON.stringify([rv.body, r]));
  check('Financeiro', '6. Saldo volta a R$ 1.000,00', Number(r.open_amount) === 1000 && Number(rv.body.open) === 1000);
  const hist = (await A.call('GET', '/finance/' + entry.id + '/settlements')).body;
  check('Financeiro', '7. Histórico mantém a baixa estornada com motivo', hist.length === 1 && hist[0].reversed_at && hist[0].reversal_reason === 'Cliente desistiu', JSON.stringify(hist));
  const db = sql(`select count(*), count(*) filter (where reversed_at is not null), count(*) filter (where amount<0), sum(amount) from finance_settlements where entry_id='${entry.id}'`)[0];
  check('Financeiro', '8. Banco: 1 baixa, 1 estornada, nenhuma negativa ou duplicada', db[0] === '1' && db[1] === '1' && db[2] === '0' && db[3] === '300.00', JSON.stringify(db));
  check('Financeiro', 'Lançamento volta a "não quitado" (settled_on nulo)', sql(`select settled_on is null from finance_entries where id='${entry.id}'`)[0][0] === 't');
  const rv2 = await A.call('POST', '/finance/settlements/' + p300.body.settlementId + '/reverse', { reason: 'de novo' });
  check('Financeiro', 'Estornar duas vezes a mesma baixa é recusado', rv2.status === 400, 'HTTP ' + rv2.status);
  // Vários pagamentos e arredondamento
  const thirds = [];
  for (const v of [333.33, 333.33, 333.34]) thirds.push(await A.call('POST', '/finance/' + entry.id + '/settlements', { amount: v }));
  r = await row();
  check('Financeiro', 'Três pagamentos (333,33 + 333,33 + 333,34) quitam exatamente', thirds.every(t => t.status === 200) && Number(r.open_amount) === 0 && r.settled_on, JSON.stringify(r));
  check('Financeiro', 'Pagamento extra de R$ 0,01 depois de quitado é recusado', (await A.call('POST', '/finance/' + entry.id + '/settlements', { amount: 0.01 })).status === 400);
  const e3 = (await A.call('POST', '/finance', { direction: 'INCOME', category: 'Teste', description: 'Arredondamento', amount: 10, settled: false })).body;
  const tiny = await A.call('POST', '/finance/' + e3.id + '/settlements', { amount: 3.333 });
  note('Financeiro', 'Pagamento com 3 casas (3,333)', 'HTTP ' + tiny.status + '; guardado ' + (sql(`select string_agg(amount::text,',') from finance_settlements where entry_id='${e3.id}'`)[0][0] || '-'));
  // Contas a pagar
  const payBefore = Number((await A.call('GET', '/finance/summary')).body.payable);
  const exp = (await A.call('POST', '/finance', { direction: 'EXPENSE', category: 'Aluguel', description: 'Aluguel auditoria', amount: 500, settled: false })).body;
  const pay = await A.call('POST', '/finance/' + exp.id + '/settlements', { amount: 200 });
  check('Financeiro', 'Conta a pagar de 500 com pagamento parcial de 200 deixa 300 a pagar', pay.status === 200 &&
    Math.abs(Number((await A.call('GET', '/finance/summary')).body.payable) - payBefore - 300) < 0.005, JSON.stringify(pay.body));
  check('Financeiro', 'Valor negativo em lançamento recusado', (await A.call('POST', '/finance', { direction: 'INCOME', category: 'X', description: 'Y', amount: -1 })).status === 400);
  check('Financeiro', 'Baixa com data futura recusada', (await A.call('POST', '/finance/' + exp.id + '/settlements', { amount: 10, paidOn: '2099-01-01' })).status === 400);

  // ================= 6.9 Recibos =================
  await A.call('PUT', '/receipts/settings', { legalName: 'Ótica AuditA LTDA', taxDocument: '11222333000181', address: 'Rua Grande, 100 - Centro, São Luís/MA',
    contactPhone: '(98) 3222-0000', signerName: 'Dona AuditA', signerRole: 'Sócia' });
  const keep = await sale('PIX', 1234.56);
  const keepEntry = sql(`select st.id from finance_settlements st join finance_entries e on e.id=st.entry_id where e.sale_id='${keep.body.id}'`)[0][0];
  const iss = await A.call('POST', '/receipts/settlements/' + keepEntry + '/issue', {});
  check('Recibos', 'Emissão de recibo por recebimento', iss.status === 200, JSON.stringify(iss.body));
  const rid = iss.body && (iss.body.id || iss.body.receiptId);
  const dl = await A.call('GET', '/receipts/' + rid + '/download');
  check('Recibos', 'Download do PDF do recibo', dl.status === 200 && dl.type.includes('pdf'));
  if (dl.status === 200) {
    fs.writeFileSync(OUT + '/recibo-auditoria.pdf', dl.body);
    const text = execFileSync('pdftotext', ['-layout', OUT + '/recibo-auditoria.pdf', '-']).toString();
    fs.writeFileSync(OUT + '/recibo-auditoria.txt', text);
    check('Recibos', 'PDF mantém a razão social como cadastrada (Ótica AuditA LTDA)', /Ótica AuditA LTDA/.test(text), 'no PDF: ' + (text.match(/Emitente: .*/) || [''])[0]);
    check('Recibos', 'PDF tem o CNPJ formatado', /11\.222\.333\/0001-81/.test(text));
    check('Recibos', 'PDF tem o nome do cliente', /Marta Auditoria Silva/.test(text));
    check('Recibos', 'PDF mostra "R$ 1.234,56" sem caractere estranho', /R\$\s1\.234,56/.test(text), 'no PDF: ' + (text.match(/Valor pago pelo cliente: .*/) || [''])[0]);
    check('Recibos', 'PDF mostra a data no formato brasileiro (dd/mm/aaaa)', /\d{2}\/\d{2}\/\d{4}/.test(text), 'no PDF: ' + (text.match(/Pagamento: \S+/) || [''])[0]);
    note('Recibos', 'Logo e valor por extenso', 'Sem logo enviado nesta ótica (o código desenha o logo quando existe); o recibo não traz o valor por extenso.');
    check('Recibos', 'PDF não tem o CPF completo do cliente', !text.includes(cpfC) , 'CPF completo aparece');
  }
  const dup2 = await A.call('POST', '/receipts/settlements/' + keepEntry + '/issue', {});
  check('Recibos', 'Segunda emissão do mesmo recebimento não cria outro número', dup2.status === 200 && (dup2.body.id || dup2.body.receiptId) === rid, JSON.stringify(dup2.body));
  const share = await A.call('POST', '/receipts/' + rid + '/share', {});
  check('Recibos', 'Link público do recibo', share.status === 200, JSON.stringify(share.body));

  // ================= 6.3 / 6.10 Consultas e clínicas parceiras =================
  const clinic = (await A.call('POST', '/optometry/clinics', { name: 'Clínica Auditoria', phone: '9833334444', address: 'Rua X, 1' })).body;
  const patient = (await A.call('POST', '/optometry/patients', { fullName: 'Pedro Paciente Auditoria', cpf: cpf(), birthDate: '1990-01-01', phone: '98966660000', address: 'Rua Y' })).body;
  const day = sql(`select ((now() at time zone 'America/Sao_Paulo')::date + 3)::text`)[0][0];
  const at = h => day + 'T' + h + ':00-03:00';
  const ap1 = await A.call('POST', '/optometry/appointments', { patientId: patient.id, clinicId: clinic.id, startsAt: at('09:00'), endsAt: at('09:30'), notes: '' });
  check('Consultas', 'Agendamento 09:00-09:30 (horário de Brasília)', ap1.status === 200, JSON.stringify(ap1.body));
  const conflict = await A.call('POST', '/optometry/appointments', { patientId: patient.id, clinicId: clinic.id, startsAt: at('09:15'), endsAt: at('09:45'), notes: '' });
  check('Consultas', 'Conflito de horário na mesma clínica recusado', conflict.status === 400, 'HTTP ' + conflict.status);
  const inverted = await A.call('POST', '/optometry/appointments', { patientId: patient.id, clinicId: clinic.id, startsAt: at('10:00'), endsAt: at('09:00'), notes: '' });
  check('Consultas', 'Fim antes do início recusado', inverted.status === 400);
  const pastAp = await A.call('POST', '/optometry/appointments', { patientId: patient.id, clinicId: clinic.id, startsAt: '2020-01-01T09:00:00-03:00', endsAt: '2020-01-01T09:30:00-03:00', notes: '' });
  check('Consultas', 'Agendamento no passado recusado', pastAp.status === 400, 'HTTP ' + pastAp.status + ' (aceitou consulta em 2020)');
  const late = await A.call('POST', '/optometry/appointments', { patientId: patient.id, clinicId: clinic.id, startsAt: at('23:30'), endsAt: at('23:59'), notes: '' });
  check('Consultas', 'Horário fora do expediente (23h30) recusado ou avisado', late.status === 400, 'HTTP ' + late.status + ' (sem validação de horário de funcionamento)');
  const list = (await A.call('GET', '/optometry/appointments?day=' + day)).body;
  const got = Array.isArray(list) ? list.find(x => x.id === ap1.body.id) : null;
  check('Consultas', 'Consulta aparece no dia certo (fuso da ótica)', !!got, JSON.stringify(list).slice(0, 200));
  for (const st of ['CONFIRMED', 'COMPLETED'])
    check('Consultas', 'Status ' + st, (await A.call('PATCH', '/optometry/appointments/' + ap1.body.id + '/status', { status: st })).status === 200);
  check('Consultas', 'Status não volta de COMPLETED', (await A.call('PATCH', '/optometry/appointments/' + ap1.body.id + '/status', { status: 'CANCELLED' })).status === 400);
  note('Consultas', 'Editar/remarcar consulta e editar clínica', 'Não há endpoint para remarcar consulta, editar clínica parceira ou editar paciente (só criar e mudar status/autorização).');

  // ================= 6.11 Visita em domicílio opcional =================
  const slugA = sql(`select slug from optics where id='${A.opticId}'`)[0][0];
  const anon = await new Client('anon').init();
  const closed = await anon.call('GET', '/public/site/' + slugA);
  check('Visitas', 'Site fechado: página pública não abre', closed.status >= 400 && closed.status < 500, 'HTTP ' + closed.status);
  note('Visitas', 'Código do site fechado', 'HTTP ' + closed.status + ' ' + closed.text);
  check('Visitas', 'Operador libera o site da ótica A', (await op.call('PATCH', '/platform/optics/' + A.opticId, { siteEnabled: true })).status === 200);
  const siteOn = await anon.call('GET', '/public/site/' + slugA);
  check('Visitas', 'Site aberto com visita em domicílio desligada por padrão', siteOn.status === 200 && siteOn.body.home_visits_enabled === false, JSON.stringify(siteOn.body).slice(0, 200));
  const vreq = await anon.call('POST', '/public/site/' + slugA + '/visits', { name: 'Visitante', phone: '98981234567', email: '', address: 'Rua Z, 3',
    appointmentAt: new Date(Date.now() + 3 * 86400000).toISOString(), notes: '', privacyAccepted: true, prescriptionMime: 'application/pdf', prescriptionName: 'receita.pdf', prescriptionBase64: pdf });
  check('Visitas', 'Pedido de visita recusado quando o recurso está desligado', vreq.status >= 400 && vreq.status < 500, 'HTTP ' + vreq.status + ' ' + JSON.stringify(vreq.body));
  check('Visitas', 'Sem visita ativada, a ótica vende, recebe e emite recibo normalmente', pix.status === 200 && iss.status === 200);

  // ================= 6.12 OS =================
  const os = await A.call('POST', '/service-orders', { kind: 'LENTES', saleId: keep.body.id, supplierId: sup.id, description: 'Lentes OD -2,00', expectedOn: null, price: 150.75 });
  check('OS', 'Abrir OS ligada a venda e fornecedor', os.status === 200, JSON.stringify(os.body));
  check('OS', 'Pular etapa ABERTA → ENTREGUE recusado', (await A.call('PATCH', '/service-orders/' + os.body.id + '/status', { status: 'ENTREGUE' })).status === 400);
  check('OS', 'Data futura na mudança de status recusada', (await A.call('PATCH', '/service-orders/' + os.body.id + '/status', { status: 'ENVIADA', date: '2099-01-01' })).status === 400);
  check('OS', 'Fornecedor desativado', (await A.call('PATCH', '/suppliers/' + sup.id + '/active', { active: false })).status === 200);
  const osInactive = await A.call('POST', '/service-orders', { kind: 'LENTES', saleId: keep.body.id, supplierId: sup.id, description: 'X', expectedOn: null, price: null });
  check('OS', 'OS com fornecedor desativado recusada', osInactive.status === 400, 'HTTP ' + osInactive.status);
  const osB = await B.call('POST', '/service-orders', { kind: 'LENTES', saleId: keep.body.id, supplierId: null, description: 'X', expectedOn: null, price: null });
  check('Isolamento', 'B não abre OS usando venda de A', osB.status === 400 || osB.status === 404, 'HTTP ' + osB.status);

  // ================= 6.14 Papéis e convites =================
  const inv = (await A.call('POST', '/team/invites', { email: 'vend' + Date.now() + '@e2e.test', role: 'SELLER', permissions: null })).body;
  const token = new URL(inv.inviteUrl).searchParams.get('invite');
  const seller = await new Client('seller').init();
  const accBody = { token, fullName: 'Vera Vendedora', password: 'SenhaVendedora2026', cpf: cpf(), phone: '98987654321',
    address: 'Rua V, 5', birthDate: '1995-05-05', acceptedTerms: true };
  const acc = await seller.call('POST', '/public/accept-invite', accBody);
  check('Permissões', 'Convite aceito pela vendedora', acc.status === 200, JSON.stringify(acc.body));
  const sellerEmail = sql(`select invited_email from member_invitations where optic_id='${A.opticId}' order by created_at desc limit 1`)[0][0];
  check('Permissões', 'Login da vendedora', (await seller.login(sellerEmail, 'SenhaVendedora2026')).status === 200);
  check('Permissões', 'Convite não pode ser usado de novo', (await new Client('x').init().then(c => c.call('POST', '/public/accept-invite', { ...accBody, cpf: cpf() }))).status === 400);
  for (const [m, p, d] of [['GET', '/finance', undefined], ['GET', '/finance/summary', undefined], ['POST', '/finance', { direction: 'INCOME', category: 'X', description: 'Y', amount: 1 }],
    ['GET', '/team', undefined], ['POST', '/team/invites', { email: 'z@e2e.test', role: 'MANAGER', permissions: [] }], ['PUT', '/receipts/settings', {}],
    ['GET', '/messages/settings', undefined], ['POST', '/machines', {}], ['PATCH', '/catalog/' + frame.id, { brand: 'X', model: 'Y' }], ['GET', '/platform/optics', undefined]]) {
    const x = await seller.call(m, p, d);
    check('Permissões', 'Vendedora não acessa ' + m + ' ' + p, x.status === 403, 'HTTP ' + x.status);
  }
  check('Permissões', 'Vendedora vê clientes', (await seller.call('GET', '/customers')).status === 200);
  const sellerSale = await seller.call('POST', '/sales', { customerId: c1.body.id, lensId: lens.id, frameId: frame.id, amount: 50, paymentMethod: 'PIX', cardMachineId: null,
    installments: 1, passFee: false, settlementDays: null, replacementMonths: 12, expectedDelivery: null, notes: '', idempotencyKey: 'seller-' + Date.now() });
  check('Permissões', 'Vendedora registra venda', sellerSale.status === 200, JSON.stringify(sellerSale.body));
  check('Permissões', 'Vendedora não troca para a ótica B', (await seller.call('POST', '/auth/select-optic', { opticId: B.opticId })).status === 403);

  // ================= Outros controles de segurança =================
  check('Segurança', 'POST sem token CSRF recusado (403)', (await A.call('POST', '/customers', { fullName: 'Sem CSRF' }, { noCsrf: true })).status === 403);
  const forgotAnon = await new Client('f').init();
  const f1 = await forgotAnon.call('POST', '/public/password/forgot', { email: A.email });
  const f2 = await forgotAnon.call('POST', '/public/password/forgot', { email: 'ninguem@e2e.test' });
  check('Segurança', 'Esqueci a senha: mesma resposta para conta existente e inexistente', f1.status === 200 && f1.text === f2.text, f1.text + ' x ' + f2.text);
  let f6; for (let i = 0; i < 4; i++) f6 = await forgotAnon.call('POST', '/public/password/forgot', { email: 'x' + i + '@e2e.test' });
  check('Segurança', 'Esqueci a senha: 6º pedido em 15 min = 429', f6.status === 429, 'HTTP ' + f6.status);
  check('Segurança', 'Redefinir senha com link inválido recusado', (await forgotAnon.call('POST', '/public/password/reset', { token: 'x'.repeat(43), password: 'NovaSenha2026xx' })).status === 400);
  const malformed = await A.call('POST', '/customers', '{"fullName": ', { raw: true });
  check('Segurança', 'JSON malformado: 400 sem stack trace', malformed.status === 400 && !/Exception|at br\.com|\.java/.test(malformed.text), 'HTTP ' + malformed.status + ' ' + malformed.text.slice(0, 200));
  const badUuid = await A.call('GET', '/finance/nao-e-uuid/settlements');
  check('Segurança', 'ID inválido na URL: erro 4xx sem stack trace', badUuid.status >= 400 && badUuid.status < 500 && !/Exception|\.java/.test(badUuid.text), 'HTTP ' + badUuid.status + ' ' + badUuid.text.slice(0, 200));
  check('Segurança', 'Actuator /env bloqueado mesmo com login', (await A.call('GET', '/actuator/env', undefined, { abs: true })).status === 403);
  const sqliRep = await A.call('GET', '/reports/detailed/vendas?frameBrand=' + encodeURIComponent("' OR 1=1--"));
  check('Segurança', 'SQL injection no filtro de relatório: 0 linhas, sem erro', sqliRep.status === 200 && sqliRep.body.totalRows === 0, 'HTTP ' + sqliRep.status);
  const siteXss = await A.call('PUT', '/site-settings', { ...((await A.call('GET', '/site-settings')).body), primaryColor: '#7c3aed', secondaryColor: '#1e1b4b',
    headline: '<img src=x onerror="window.__xss=3">Óculos', subtitle: 'Sub', about: '<script>window.__xss=4</script>', homeVisitsEnabled: false,
    services: [], testimonials: [], faqs: [], template: 'acolhedor', mood: 'suave', fonts: 'suave', hours: '', instagram: '', showMap: false });
  note('Segurança', 'Site com texto contendo HTML salvo', 'HTTP ' + siteXss.status + ' (verificação de execução no teste visual)');
  check('Segurança', 'Cor do site fora do formato recusada (evita CSS injection)', (await A.call('PUT', '/site-settings', { ...((await A.call('GET', '/site-settings')).body),
    primaryColor: 'red;background:url(//evil)', homeVisitsEnabled: false })).status === 400);

  // ================= 6.13 Isolamento: varredura de todas as rotas com ID (B usando IDs de A) =================
  const id1 = (q) => (sql(q)[0] || [])[0];
  const ids = {
    catalog: frame.id, customer: c1.body.id, sale: keep.body.id, machine: mach.id, entry: entry.id, settlement: keepEntry,
    receipt: rid, share: id1(`select id from receipt_share_tokens where optic_id='${A.opticId}' limit 1`),
    attachment: id1(`select id from file_attachments where optic_id='${A.opticId}' limit 1`), user: A.accountId,
    clinic: clinic.id, patient: patient.id, appointment: ap1.body.id, os: os.body.id, supplier: sup.id,
    paymentEntry: id1(`select id from finance_entries where sale_id='${keep.body.id}'`),
  };
  // visita de A para as rotas de visita: liga a visita, cria um pedido, desliga de novo
  const siteFull = { primaryColor: '#7c3aed', secondaryColor: '#1e1b4b', template: 'acolhedor', mood: 'suave', fonts: 'suave', headline: 'Óculos', subtitle: 'Sub',
    about: 'Sobre', hours: '', instagram: '', showMap: false, services: [], testimonials: [], faqs: [], homeVisitsEnabled: true, homeVisitTitle: 'Vamos até você', homeVisitDescription: 'Receita' };
  const on = await A.call('PUT', '/site-settings', siteFull);
  check('Visitas', 'Ótica liga a visita em domicílio no próprio site', on.status === 200, JSON.stringify(on.body));
  const anon2 = await new Client('anon2').init();
  await anon2.call('POST', '/public/site/' + slugA + '/visits', { name: 'Rosa', phone: '98981234567', email: '', address: 'Rua Z, 3',
    appointmentAt: new Date(Date.now() + 3 * 86400000).toISOString(), notes: '', privacyAccepted: true, prescriptionMime: 'application/pdf', prescriptionName: 'receita.pdf', prescriptionBase64: pdf });
  ids.visit = id1(`select id from home_visits where optic_id='${A.opticId}' limit 1`);
  check('Visitas', 'Com a visita ligada, o pedido pelo site é aceito', !!ids.visit);
  ids.attachment = id1(`select id from file_attachments where optic_id='${A.opticId}' and visit_id is not null limit 1`) || ids.attachment;
  const tables = ['customers', 'sales', 'catalog_items', 'card_machines', 'finance_entries', 'finance_settlements', 'sale_receipts', 'receipt_share_tokens',
    'file_attachments', 'optical_partner_clinics', 'optometry_patients', 'optical_appointments', 'service_orders', 'suppliers', 'home_visits',
    'stock_movements', 'optic_memberships', 'member_permission_overrides', 'clinic_calendar_feeds', 'site_settings', 'optic_receipt_identity'];
  const fingerprint = () => tables.map(t => t + ':' + sql(`select md5(coalesce(string_agg(x::text, ',' order by x::text),'')) from ${t} x where optic_id='${A.opticId}'`)[0][0]).join(' ');
  const before = fingerprint();
  const sweep = [
    ['PATCH', `/catalog/${ids.catalog}`, { brand: 'Hack', model: 'Hack', category: 'SOLAR' }], ['PATCH', `/catalog/${ids.catalog}/active`, { active: false }],
    ['PUT', `/birthdays/${ids.customer}/consent`, { allowed: false }], ['POST', `/birthdays/${ids.customer}/whatsapp-opened`, {}],
    ['PUT', `/customers/${ids.customer}`, { fullName: 'Hack', cpf: cpf(), birthDate: '1990-01-01', phone: '1', address: 'x' }],
    ['POST', `/retention/${ids.sale}/contact-opened`, {}], ['PUT', `/machines/${ids.machine}`, { name: 'Hack', debitFee: 1, creditFee: 1, fixedFee: 0, debitDays: 1, creditDays: 30, anticipated: false, installmentFees: [], creditTerms: [{ days: 30, fee: 1 }] }],
    ['DELETE', `/machines/${ids.machine}`], ['PATCH', `/finance/${ids.entry}/settle`, {}], ['POST', `/finance/${ids.entry}/settlements`, { amount: 1 }],
    ['GET', `/finance/${ids.entry}/settlements`], ['POST', `/finance/settlements/${ids.settlement}/reverse`, { reason: 'x' }], ['PATCH', `/finance/${ids.entry}/reverse`, {}],
    ['PATCH', `/team/${ids.user}`, { role: 'SELLER', active: false }], ['PUT', `/team/${ids.user}/permission`, { permission: 'FINANCE_READ', effect: 'DENY' }],
    ['GET', `/optometry/clinics/${ids.clinic}/calendar-feed`], ['POST', `/optometry/clinics/${ids.clinic}/calendar-feed`, {}], ['POST', `/optometry/clinics/${ids.clinic}/calendar-feed/revoke`, {}],
    ['GET', `/optometry/patients/${ids.patient}/sales-prefill`], ['PUT', `/optometry/patients/${ids.patient}/reminder`, { email: 'x@e2e.test', reminderOptIn: true }],
    ['PATCH', `/optometry/appointments/${ids.appointment}/status`, { status: 'CANCELLED' }],
    ['POST', `/attachments/customers/${ids.customer}`, { filename: 'r.pdf', mime: 'application/pdf', dataBase64: pdf }], ['GET', `/attachments/customers/${ids.customer}`],
    ['GET', `/attachments/visits/${ids.visit}`], ['GET', `/attachments/${ids.attachment}/download`],
    ['GET', `/receipts/sales/${ids.sale}/payments`], ['POST', `/receipts/payments/${ids.paymentEntry}/issue`, {}], ['POST', `/receipts/settlements/${ids.settlement}/issue`, {}],
    ['GET', `/receipts/${ids.receipt}/download`], ['POST', `/receipts/${ids.receipt}/share`, {}], ['DELETE', `/receipts/shares/${ids.share}`],
    ['PATCH', `/sales/${ids.sale}/status`, { status: 'CANCELLED' }], ['PATCH', `/service-orders/${ids.os}/status`, { status: 'CANCELADA' }], ['GET', `/service-orders/${ids.os}/events`],
    ['PATCH', `/stock/items/${ids.catalog}`, { trackStock: false, minStock: 0 }], ['GET', `/stock/items/${ids.catalog}/movements`],
    ['POST', '/stock/movements', { itemId: ids.catalog, kind: 'ENTRADA', quantity: 1 }],
    ['PUT', `/suppliers/${ids.supplier}`, { name: 'Hack', kind: 'FORNECEDOR' }], ['PATCH', `/suppliers/${ids.supplier}/active`, { active: true }],
    ['GET', `/visits/${ids.visit}/events`], ['POST', `/visits/${ids.visit}/claim`, {}], ['PATCH', `/visits/${ids.visit}/status`, { status: 'CANCELLED' }],
    ['POST', `/visits/${ids.visit}/convert`, {}], ['DELETE', `/visits/${ids.visit}`], ['PATCH', `/platform/optics/${A.opticId}`, { siteEnabled: false }],
    ['POST', '/auth/select-optic', { opticId: A.opticId }],
    ['POST', '/sales', { customerId: ids.customer, lensId: lens.id, frameId: frame.id, amount: 10, paymentMethod: 'PIX', installments: 1, replacementMonths: 12, idempotencyKey: 'h' + Date.now() }],
    ['POST', '/service-orders', { kind: 'LENTES', saleId: ids.sale, description: 'x' }],
    ['POST', '/optometry/appointments', { patientId: ids.patient, clinicId: ids.clinic, startsAt: at('15:00'), endsAt: at('15:30') }],
    ['GET', `/reports/detailed/vendas?sellerId=${A.accountId}`], ['GET', `/reports/detailed/consultas?clinicId=${ids.clinic}`],
  ];
  const leaks = [];
  for (const [m, p, d] of sweep) {
    const res = await B.call(m, p, d);
    const containsA = /Marta|AuditA|Clínica Auditoria|Pedro Paciente|Fornecedor Auditoria|Zeiss|Oakley|Rosa/.test(res.text);
    if (containsA) leaks.push(m + ' ' + p + ' → HTTP ' + res.status + ' ' + (res.text.match(/.{0,60}(Marta|AuditA|Clínica Auditoria|Pedro Paciente|Fornecedor Auditoria|Zeiss|Oakley|Rosa).{0,40}/) || [res.text.slice(0, 120)])[0]);
    else if (res.status !== 404) results.push({ cat: 'Isolamento-detalhe', name: m + ' ' + p, ok: null, detail: 'HTTP ' + res.status + ' ' + res.text.slice(0, 120) });
  }
  check('Isolamento', `Varredura: ${sweep.length} rotas chamadas pela ótica B com IDs da ótica A — nenhuma resposta contém dados de A`, leaks.length === 0, leaks.join(' | '));
  const after = fingerprint();
  check('Isolamento', 'Banco: dados da ótica A idênticos antes e depois da varredura (21 tabelas)', before === after,
    before.split(' ').filter((x, i) => x !== after.split(' ')[i]).join(' '));
  const non404 = results.filter(r => r.cat === 'Isolamento-detalhe');
  note('Isolamento', 'Códigos de resposta na varredura', `${sweep.length - non404.length - leaks.length} rotas responderam 404; ${non404.length} responderam outro código, sem dados de A: ` + non404.map(r => r.name.replace(/[0-9a-f-]{36}/g, '{id}') + '=' + r.detail.slice(5, 8)).join(', '));
  // listas de B não contêm nada de A
  for (const p of ['/customers', '/sales', '/finance', '/catalog', '/suppliers', '/stock', '/service-orders', '/optometry/patients', '/optometry/clinics', '/visits', '/receipts', '/team'])
    { const res = await B.call('GET', p); check('Isolamento', 'Lista ' + p + ' de B sem dados de A', !/Marta|AuditA|Auditoria|Zeiss|Oakley/.test(res.text), 'HTTP ' + res.status + ' ' + res.text.slice(0, 150)); }
  check('Isolamento', 'Recibo de A pelo link público funciona só com o token, não pelo ID', (await anon.call('GET', '/public/receipts/' + rid)).status === 404);

  fs.writeFileSync(OUT + '/audit-ids.json', JSON.stringify({ A: { email: A.email, opticId: A.opticId, slug: slugA }, B: { email: B.email, opticId: B.opticId },
    seller: sellerEmail, entry: entry.id, ids }, null, 2));
}

(async () => {
  try {
    if (PHASE === 'validacao') await validationPhase(); else await mainPhase();
  } catch (e) { check('Execução', 'Script terminou sem exceção', false, e.stack); }
  fs.writeFileSync(OUT + '/audit-' + PHASE + '.json', JSON.stringify(results, null, 2));
  const c = results.filter(r => r.ok !== null);
  console.log(`\nTOTAL ${PHASE}: ${c.length} verificações, ${c.filter(r => r.ok).length} OK, ${c.filter(r => !r.ok).length} falhas, ${results.filter(r => r.ok === null && r.cat !== 'Isolamento-detalhe').length} notas`);
})();
