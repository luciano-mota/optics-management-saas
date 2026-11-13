// Teste ponta a ponta do Optics Management: API real (sessão + CSRF) e passada visual nas telas.
const { request, chromium } = require('playwright');
const crypto = require('crypto');
const BASE = process.env.BASE || 'http://localhost:8080';
const UI = process.env.UI || 'http://localhost:5173';
const OUT = process.argv[2];
const results = [];
const fail = [];
function check(name, ok, detail = '') {
  results.push((ok ? 'OK   ' : 'FALHA') + ' ' + name + (detail ? ' — ' + detail : ''));
  if (!ok) fail.push(name + (detail ? ' — ' + detail : ''));
}
function cpf() {
  const d = Array.from({ length: 9 }, () => Math.floor(Math.random() * 10));
  for (const len of [9, 10]) { let s = 0; for (let i = 0; i < len; i++) s += d[i] * (len + 1 - i); const c = 11 - (s % 11); d.push(c >= 10 ? 0 : c); }
  return d.join('');
}
const pdf = Buffer.from('%PDF-1.4\n% receita de teste\n').toString('base64');
const today = new Date().toISOString().slice(0, 10);

/** Código TOTP (RFC 6238) como o aplicativo autenticador geraria. */
function totp(secret, offset = 0) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of secret.replace(/\s/g, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  const msg = Buffer.alloc(8); msg.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000) + offset));
  const h = crypto.createHmac('sha1', Buffer.from(bytes)).update(msg).digest();
  const o = h[h.length - 1] & 15;
  const n = ((h[o] & 127) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1000000).padStart(6, '0');
}

class Client {
  constructor(name) { this.name = name; }
  async init() { this.ctx = await request.newContext({ baseURL: BASE }); await this.csrf(); return this; }
  async csrf() { const r = await this.ctx.get('/api/v1/auth/csrf'); this.token = (await r.json()).token; }
  async call(method, path, data, form) {
    const headers = method === 'GET' ? {} : { 'X-XSRF-TOKEN': this.token };
    const opts = { headers };
    if (data !== undefined) { if (form) opts.form = data; else opts.data = data; }
    const r = await this.ctx.fetch('/api/v1' + path, { method, ...opts });
    let body = null; const type = r.headers()['content-type'] || '';
    if (type.includes('json')) body = await r.json().catch(() => null); else body = await r.body();
    return { status: r.status(), body, type };
  }
  async login(email, password) {
    const r = await this.call('POST', '/auth/login', { email, password }, true);
    await this.csrf();
    return r.status;
  }
}

async function signupOptic(label) {
  const c = await new Client(label).init();
  const email = label.toLowerCase().replace(/\W/g, '') + Date.now() + '@e2e.test';
  const s = await c.call('POST', '/public/signup', { opticName: 'Ótica ' + label, fullName: 'Dona ' + label, email,
    password: 'SenhaSegura2026', whatsapp: '98999990000', acceptedTerms: true });
  check(label + ': cadastro público', s.status === 200, 'HTTP ' + s.status + ' ' + JSON.stringify(s.body));
  check(label + ': login', await c.login(email, 'SenhaSegura2026') === 200);
  let me = (await c.call('GET', '/auth/me')).body;
  check(label + ': primeiro acesso pede CNPJ e endereço', me.opticRegistrationRequired === true);
  const p = await c.call('PUT', '/auth/profile', { fullName: 'Dona ' + label, cpf: cpf(), phone: '98999990000',
    birthDate: '1988-04-12', address: null, opticCnpj: '11.222.333/0001-81',
    opticAddress: { address: 'Rua Grande, 100 - Centro, São Luís/MA - CEP 65020-250', city: 'São Luís', uf: 'MA', ibge: '2111300' } });
  check(label + ': conclui primeiro acesso', p.status === 200, JSON.stringify(p.body));
  me = (await c.call('GET', '/auth/me')).body;
  check(label + ': painel liberado após cadastro', me.opticRegistrationRequired === false && me.profileComplete === true);
  c.email = email; c.me = me; c.opticId = me.activeOpticId;
  return c;
}

(async () => {
  // ---------- Operador LCM ----------
  const op = await new Client('Operador').init();
  check('Operador: login com variáveis de ambiente', await op.login('operador@lcm.test', 'SenhaForte2026x') === 200);
  const opMe = (await op.call('GET', '/auth/me')).body;
  check('Operador: é operador da plataforma', opMe.platformOperator === true);

  // ---------- Ótica A ----------
  const a = await signupOptic('Alfa');
  const cal = (await a.call('GET', '/business-calendar/settings')).body;
  check('Calendário: localização vem do endereço', cal.state_uf === 'MA' && cal.municipality_name === 'São Luís', JSON.stringify(cal));
  const july = (await a.call('GET', '/business-calendar/month?year=2026&month=7')).body;
  const d2807 = july.days.find(d => d.date === '2026-07-28');
  check('Calendário: feriado estadual do MA fecha a loja', d2807 && d2807.willOpen === false);
  check('Calendário: salvar dias de abertura', (await a.call('PUT', '/business-calendar/settings', { openWeekdays: [1, 2, 3, 4, 5, 6] })).status === 200);

  const lens = (await a.call('POST', '/catalog', { kind: 'LENS', brand: 'Essilor', model: 'Varilux', category: 'MULTIFOCAL' })).body;
  const frame = (await a.call('POST', '/catalog', { kind: 'FRAME', brand: 'Ray-Ban', model: 'RB5154' })).body;
  check('Catálogo: lente e armação', !!lens.id && !!frame.id, JSON.stringify([lens, frame]));
  check('Catálogo: completar categoria da armação', (await a.call('PATCH', '/catalog/' + frame.id,
    { brand: 'Ray-Ban', model: 'RB5154', category: 'SOLAR' })).status === 200);
  check('Catálogo: tipo de lente fora da lista recusado', (await a.call('POST', '/catalog',
    { kind: 'LENS', brand: 'X', category: 'SOLAR' })).status === 400);
  const frames = (await a.call('GET', '/catalog?kind=FRAME')).body;
  check('Catálogo: categoria salva', frames[0] && frames[0].category === 'SOLAR', JSON.stringify(frames));

  const machine = await a.call('POST', '/machines', { name: 'Stone', debitFee: 1.49, creditFee: 3.19, fixedFee: 0,
    debitDays: 1, creditDays: 30, anticipated: false,
    installmentFees: [{ installments: 2, saleFee: 4.2, installmentFee: 0 }, { installments: 3, saleFee: 4.9, installmentFee: 0 }],
    creditTerms: [{ days: 30, fee: 3.19 }] });
  check('Maquininha: cadastro', machine.status >= 200 && machine.status < 300, JSON.stringify(machine.body));
  const machineId = machine.body.id;
  const preview = await a.call('POST', '/machines/preview', { amount: 900, paymentMethod: 'CREDITO', installments: 3,
    cardMachineId: machineId, passFee: false, settlementDays: null });
  check('Maquininha: simulação 3x', preview.status === 200 && Number(preview.body.netAmount) < 900, JSON.stringify(preview.body));

  const customer = await a.call('POST', '/customers', { fullName: 'Carla Cliente', cpf: cpf(), birthDate: '1990-11-13',
    phone: '(98) 98888-7777', address: 'Rua das Flores, 10 - Centro, São Luís/MA - CEP 65010-000' });
  check('Cliente: cadastro', customer.status === 200, JSON.stringify(customer.body));
  const att = await a.call('POST', '/attachments/customers/' + customer.body.id, { filename: 'receita.pdf', mime: 'application/pdf', dataBase64: pdf });
  check('Cliente: receita anexada', att.status === 200, JSON.stringify(att.body));

  const salePix = await a.call('POST', '/sales', { customerId: customer.body.id, lensId: lens.id, frameId: frame.id, amount: 650,
    paymentMethod: 'PIX', cardMachineId: null, installments: 1, passFee: false, settlementDays: null, replacementMonths: 12,
    expectedDelivery: null, notes: '', idempotencyKey: 'e2e-' + Date.now() });
  check('Venda: Pix com cliente existente', salePix.status === 200, JSON.stringify(salePix.body));
  const saleCredit = await a.call('POST', '/sales/with-customer', { customer: { fullName: 'Diego Novo', cpf: cpf(),
    birthDate: '1985-02-20', phone: '98977776666', address: 'Av. Litorânea, 5 - Calhau, São Luís/MA' },
    sale: { customerId: null, lensId: lens.id, frameId: frame.id, amount: 900, paymentMethod: 'CREDITO', cardMachineId: machineId,
      installments: 3, passFee: false, settlementDays: null, replacementMonths: 6, expectedDelivery: null, notes: '', idempotencyKey: 'e2e-c-' + Date.now() } });
  check('Venda: crédito 3x com cliente novo', saleCredit.status === 200, JSON.stringify(saleCredit.body));
  const dash = (await a.call('GET', '/sales/dashboard')).body;
  check('Painel: vendas de hoje', Number(dash.today) === 2, JSON.stringify(dash));
  const fin = (await a.call('GET', '/finance/summary')).body;
  check('Financeiro: recebível do cartão separado do caixa', Number(fin.receivable) > 0, JSON.stringify(fin));

  for (const st of ['READY_FOR_DELIVERY', 'DELIVERED'])
    check('Venda: status ' + st, (await a.call('PATCH', '/sales/' + salePix.body.id + '/status', { status: st })).status === 200);
  check('Venda: não volta de entregue', (await a.call('PATCH', '/sales/' + salePix.body.id + '/status', { status: 'READY_FOR_DELIVERY' })).status === 400);

  check('Recibo: identidade da ótica', (await a.call('PUT', '/receipts/settings', { legalName: 'Ótica Alfa LTDA', taxDocument: '11222333000181',
    address: 'Rua Grande, 100 - Centro, São Luís/MA', contactPhone: '(98) 3222-0000', signerName: 'Dona Alfa', signerRole: 'Sócia' })).status === 200);
  const payments = (await a.call('GET', '/receipts/sales/' + salePix.body.id + '/payments')).body;
  const paid = Array.isArray(payments) ? payments.find(p => p.settled_on || p.settledOn) || payments[0] : null;
  check('Recibo: pagamento da venda Pix', !!paid, JSON.stringify(payments));
  if (paid) {
    const issued = await a.call('POST', '/receipts/payments/' + (paid.id || paid.payment_id) + '/issue', {});
    check('Recibo: emissão', issued.status === 200, JSON.stringify(issued.body));
    const rid = issued.body && (issued.body.id || issued.body.receiptId);
    if (rid) {
      const dl = await a.call('GET', '/receipts/' + rid + '/download');
      check('Recibo: PDF', dl.status === 200 && dl.type.includes('pdf'), dl.type);
    }
  }

  for (const f of ['csv', 'xlsx', 'pdf']) {
    const r = await a.call('GET', '/reports/sales.' + f);
    check('Relatório de vendas .' + f, r.status === 200 && r.body.length > 50, 'HTTP ' + r.status);
  }
  check('Meta do mês', (await a.call('PUT', '/sales-goals/' + today.slice(0, 4) + '/' + Number(today.slice(5, 7)), { goalAmount: 120000 })).status === 200);
  const summary = await a.call('GET', '/reports/monthly-sales-summary?year=' + today.slice(0, 4) + '&month=' + Number(today.slice(5, 7)) + '&asOf=' + today);
  check('Relatório Vendas Atualizadas (14 indicadores)', summary.status === 200 && summary.body.indicators && summary.body.indicators.length >= 14,
    'HTTP ' + summary.status + ' ' + JSON.stringify(summary.body).slice(0, 200));
  const sumPdf = await a.call('GET', '/reports/monthly-sales-summary.pdf?year=' + today.slice(0, 4) + '&month=' + Number(today.slice(5, 7)) + '&asOf=' + today);
  check('Vendas Atualizadas em PDF', sumPdf.status === 200, 'HTTP ' + sumPdf.status);

  // ---------- Detalhe de cada indicador: a soma da lista é o número do resumo ----------
  const s14 = summary.body.summary;
  const ym = 'year=' + s14.year + '&month=' + s14.month + '&asOf=' + s14.asOf;
  const sumCol = (rep, header) => { const i = rep.headers.indexOf(header);
    return rep.rows.reduce((t, r) => t + (r[i] === '' ? 0 : Number(r[i])), 0); };
  for (const [kind, header, key] of [['vendas', 'Valor da venda', 'totalSales'], ['recebidos', 'Líquido', 'netReceived'],
    ['a-receber', 'Líquido', 'toReceive'], ['taxas', 'Taxa', 'bankFees']]) {
    const d = await a.call('GET', '/reports/monthly-sales-summary/detail/' + kind + '?' + ym);
    check('Detalhe do indicador ' + kind + ' soma igual ao resumo', d.status === 200 &&
      Math.abs(sumCol(d.body, header) - Number(s14[key])) < 0.005, 'HTTP ' + d.status + ' ' + (d.body && sumCol(d.body, header)) + ' x ' + s14[key]);
  }
  for (const kind of ['dias', 'metas']) {
    const d = await a.call('GET', '/reports/monthly-sales-summary/detail/' + kind + '?' + ym);
    check('Detalhe do indicador ' + kind, d.status === 200 && d.body.rows.length >= 28, 'HTTP ' + d.status);
  }
  const detailPdf = await a.call('GET', '/reports/monthly-sales-summary/detail/a-receber/export/pdf?' + ym);
  check('Detalhe do indicador em PDF', detailPdf.status === 200 && detailPdf.type.includes('pdf'));

  const clinic = await a.call('POST', '/optometry/clinics', { name: 'Clínica Olhar', phone: '(98) 3333-4444', address: 'Rua X, 1 - Centro, São Luís/MA' });
  const patientCpf = cpf();
  const patient = await a.call('POST', '/optometry/patients', { fullName: 'Paula Paciente', cpf: patientCpf, birthDate: '1979-07-01',
    phone: '98966665555', email: null, address: 'Rua Y, 2 - Centro, São Luís/MA' });
  check('Consultas: clínica e paciente', clinic.status === 200 && patient.status === 200, JSON.stringify([clinic.body, patient.body]));
  const start = new Date(Date.now() + 2 * 86400000); const end = new Date(start.getTime() + 1800000);
  const appt = await a.call('POST', '/optometry/appointments', { patientId: patient.body.id, clinicId: clinic.body.id,
    startsAt: start.toISOString(), endsAt: end.toISOString(), notes: '' });
  check('Consultas: agendamento', appt.status === 200, JSON.stringify(appt.body));
  const custBefore = (await a.call('GET', '/customers')).body.length;
  check('Consultas: paciente não vira cliente sozinho', custBefore === 2, 'clientes=' + custBefore);
  const conv = await a.call('POST', '/sales/with-patient', { patientId: patient.body.id,
    customer: { fullName: 'Paula Paciente', cpf: patientCpf, birthDate: '1979-07-01', phone: '98966665555', address: 'Rua Y, 2 - Centro, São Luís/MA' },
    sale: { customerId: null, lensId: lens.id, frameId: frame.id, amount: 480, paymentMethod: 'DEBITO', cardMachineId: machineId, installments: 1,
      passFee: false, settlementDays: null, replacementMonths: 12, expectedDelivery: null, notes: '', idempotencyKey: 'e2e-p-' + Date.now() } });
  check('Consultas: paciente vira cliente na venda', conv.status === 200 && (await a.call('GET', '/customers')).body.length === 3, JSON.stringify(conv.body));

  // ---------- Relatórios detalhados (vendas, consultas, recibos, pacientes) ----------
  for (const kind of ['vendas', 'consultas', 'recibos', 'pacientes']) {
    const r = await a.call('GET', '/reports/detailed/' + kind);
    check('Relatório detalhado: ' + kind, r.status === 200 && r.body.totalRows > 0, 'HTTP ' + r.status + ' ' + JSON.stringify(r.body).slice(0, 200));
    for (const f of ['csv', 'xlsx', 'pdf']) {
      const x = await a.call('GET', '/reports/detailed/' + kind + '/export/' + f);
      check('Exportação ' + kind + ' .' + f, x.status === 200 && x.body.length > 50, 'HTTP ' + x.status);
    }
  }
  const patientsReport = (await a.call('GET', '/reports/detailed/pacientes')).body;
  check('Relatório de pacientes: sem CPF e com compra', !JSON.stringify(patientsReport.headers).includes('CPF')
    && JSON.stringify(patientsReport.rows).includes('Sim'), JSON.stringify(patientsReport).slice(0, 300));
  const byClinic = (await a.call('GET', '/reports/detailed/consultas?clinicId=' + clinic.body.id)).body;
  check('Relatório de consultas por clínica', byClinic.groups && byClinic.groups.length === 1, JSON.stringify(byClinic.groups));

  // ---------- Consulta de vendas com filtros e análise de vendas ----------
  const salesOptions = (await a.call('GET', '/reports/detailed/vendas/options')).body;
  check('Consulta de vendas: opções com marcas e vendedoras', salesOptions.frameBrands.includes('Ray-Ban')
    && salesOptions.lensBrands.includes('Essilor') && salesOptions.sellers.length >= 1, JSON.stringify(salesOptions).slice(0, 200));
  const allSales = (await a.call('GET', '/reports/detailed/vendas')).body;
  const byFrame = (await a.call('GET', '/reports/detailed/vendas?frameBrand=Ray-Ban')).body;
  check('Consulta de vendas: filtro por marca da armação', byFrame.totalRows === allSales.totalRows && allSales.totalRows >= 3);
  const byLensType = (await a.call('GET', '/reports/detailed/vendas?lensCategory=MULTIFOCAL')).body;
  check('Consulta de vendas: filtro por tipo de lente', byLensType.totalRows === allSales.totalRows);
  const byPix = (await a.call('GET', '/reports/detailed/vendas?paymentMethod=PIX')).body;
  const payCol = byPix.headers.indexOf('Pagamento');
  check('Consulta de vendas: filtro por pagamento', byPix.totalRows >= 1 && byPix.rows.every(r => r[payCol] === 'Pix'));
  const byOrigin = (await a.call('GET', '/reports/detailed/vendas?origin=CONSULTA')).body;
  check('Consulta de vendas: origem paciente de consulta', byOrigin.totalRows === 1, JSON.stringify(byOrigin.rows));
  check('Consulta de vendas: valor fora da lista recusado', (await a.call('GET', '/reports/detailed/vendas?paymentMethod=BOLETO')).status === 400);
  const ranking = (await a.call('GET', '/reports/sales-analysis?dimension=seller')).body;
  check('Análise de vendas: ranking por funcionária', ranking.rows && ranking.rows.length >= 1 && ranking.count >= 3, JSON.stringify(ranking).slice(0, 200));
  const top = ranking.rows[0];
  const clicked = (await a.call('GET', '/reports/detailed/vendas?from=' + ranking.from + '&to=' + ranking.to + '&sellerId=' + top.filterValue)).body;
  check('Análise de vendas: clique abre a consulta com o mesmo total', clicked.summary[0].startsWith('Vendas válidas: ' + top.count + ' '),
    clicked.summary[0] + ' x ' + top.count);
  const lensTypes = (await a.call('GET', '/reports/sales-analysis?dimension=lensCategory')).body;
  check('Análise de vendas: por tipo de lente', lensTypes.rows.some(r => r.label === 'Multifocal'), JSON.stringify(lensTypes.rows));
  const analysisXlsx = await a.call('GET', '/reports/sales-analysis/export/xlsx?dimension=frameBrand');
  check('Análise de vendas em Excel', analysisXlsx.status === 200 && analysisXlsx.body.length > 100);

  for (const p of ['/retention', '/retention/summary', '/birthdays/status', '/birthdays', '/finance', '/team', '/subscription', '/auth/alerts', '/optic-profile'])
    { const r = await a.call('GET', p); check('GET ' + p, r.status === 200, 'HTTP ' + r.status); }
  const invite = await a.call('POST', '/team/invites', { email: 'func' + Date.now() + '@e2e.test', role: 'SELLER', permissions: ['SALES_READ'] });
  check('Equipe: convite gera link mesmo sem e-mail', invite.status === 200 && /invite=/.test(invite.body.inviteUrl || ''),
    'HTTP ' + invite.status + ' ' + JSON.stringify(invite.body));

  // ---------- Site e atendimento em domicílio ----------
  const blocked = await a.call('GET', '/site-settings');
  check('Site: bloqueado até a LCM liberar', blocked.status === 403, 'HTTP ' + blocked.status);
  check('Operador: libera o site', (await op.call('PATCH', '/platform/optics/' + a.opticId, { siteEnabled: true })).status === 200);
  const settings = (await a.call('GET', '/site-settings')).body;
  const put = await a.call('PUT', '/site-settings', { primaryColor: '#7c3aed', secondaryColor: '#1e1b4b', template: 'acolhedor', mood: 'suave',
    fonts: 'suave', headline: 'Seus óculos com carinho', subtitle: 'Atendimento na loja ou na sua casa.', about: 'Ótica de família.',
    hours: 'Seg a sáb, 8h às 18h', instagram: '@oticaalfa', showMap: true, services: settings.services, testimonials: [{ author: 'Ana', text: 'Amei!' }],
    faqs: settings.faqs, homeVisitsEnabled: true, homeVisitTitle: 'Vamos até você', homeVisitDescription: 'Envie sua receita.' });
  check('Site: personalização salva', put.status === 200, JSON.stringify(put.body));
  const pub = await request.newContext({ baseURL: BASE });
  const site = await (await pub.get('/api/v1/public/site/' + settings.slug)).json();
  check('Site público: conteúdo e campanha', site.headline === 'Seus óculos com carinho' && site.home_visits_enabled === true && !('id' in site));
  const pubToken = (await (await pub.get('/api/v1/auth/csrf')).json()).token;
  const visit = await pub.post('/api/v1/public/site/' + settings.slug + '/visits', { headers: { 'X-XSRF-TOKEN': pubToken }, data: {
    name: 'Rosa Visitante', phone: '(98) 98123-4567', email: '', address: 'Rua Z, 3 - Centro, São Luís/MA - CEP 65020-250',
    appointmentAt: new Date(Date.now() + 3 * 86400000).toISOString(), notes: 'Tarde', privacyAccepted: true,
    prescriptionMime: 'application/pdf', prescriptionName: 'receita.pdf', prescriptionBase64: pdf } });
  check('Site público: pedido de visita com receita', visit.status() === 200, 'HTTP ' + visit.status());
  const alerts = (await a.call('GET', '/auth/alerts')).body;
  check('Equipe avisada da nova visita', alerts.newVisits === 1, JSON.stringify(alerts));
  const visits = (await a.call('GET', '/visits')).body;
  const v = visits[0];
  check('Visita: assumir', (await a.call('POST', '/visits/' + v.id + '/claim', {})).status === 200);
  for (const st of ['CONFIRMED', 'VISITED']) check('Visita: ' + st, (await a.call('PATCH', '/visits/' + v.id + '/status', { status: st })).status === 200);
  const vconv = await a.call('POST', '/visits/' + v.id + '/convert', { fullName: 'Rosa Visitante', cpf: cpf(), birthDate: '1970-05-05',
    lensId: lens.id, frameId: frame.id, amount: 720, paymentMethod: 'PIX', cardMachineId: null, installments: 1, passFee: false,
    settlementDays: null, replacementMonths: 12, expectedDelivery: null, notes: '' });
  check('Visita: vira venda e cliente', vconv.status === 200, JSON.stringify(vconv.body));
  const perf = await a.call('GET', '/reports/performance');
  check('Relatório por funcionário/visitas', perf.status === 200, 'HTTP ' + perf.status);

  // ---------- Ótica B: isolamento ----------
  const b = await signupOptic('Beta');
  const bCustomers = (await b.call('GET', '/customers')).body;
  check('Isolamento: B não vê clientes de A', Array.isArray(bCustomers) && bCustomers.length === 0);
  check('Isolamento: B não altera venda de A', (await b.call('PATCH', '/sales/' + saleCredit.body.sale?.id + '/status', { status: 'CANCELLED' })).status >= 400);
  const bSales = (await b.call('GET', '/sales')).body;
  check('Isolamento: B não vê vendas de A', Array.isArray(bSales) && bSales.length === 0);
  for (const kind of ['vendas', 'consultas', 'recibos', 'pacientes']) {
    const r = await b.call('GET', '/reports/detailed/' + kind);
    check('Isolamento: relatório de ' + kind + ' de B vazio', r.status === 200 && r.body.totalRows === 0, JSON.stringify(r.body).slice(0, 200));
  }
  const bOptions = (await b.call('GET', '/reports/detailed/vendas/options')).body;
  check('Isolamento: B não vê marcas de A nas opções', !bOptions.frameBrands.includes('Ray-Ban'), JSON.stringify(bOptions.frameBrands));
  const bRanking = (await b.call('GET', '/reports/sales-analysis?dimension=frameBrand')).body;
  check('Isolamento: análise de vendas de B vazia', bRanking.rows.length === 0 && bRanking.count === 0);
  const bDetail = (await b.call('GET', '/reports/monthly-sales-summary/detail/vendas?' + ym)).body;
  check('Isolamento: detalhe do indicador de B vazio', bDetail.rows.length === 0);
  check('Isolamento: B não edita produto de A', (await b.call('PATCH', '/catalog/' + frame.id, { brand: 'X', category: 'GRAU' })).status === 404);
  const foreignClinic = await b.call('GET', '/reports/detailed/consultas?clinicId=' + clinic.body.id);
  check('Isolamento: filtro com clínica de A não traz nada', foreignClinic.status === 200 && foreignClinic.body.totalRows === 0);
  const files = (await a.call('GET', '/attachments/customers/' + customer.body.id)).body;
  const bFile = await b.call('GET', '/attachments/' + files[0].id + '/download');
  check('Isolamento: B não baixa receita de A', bFile.status >= 400, 'HTTP ' + bFile.status);
  check('Isolamento: B não acessa painel LCM', (await b.call('GET', '/platform/optics')).status === 403);
  check('Isolamento: B não lê visitas de A', ((await b.call('GET', '/visits')).body || []).length === 0);

  // ---------- Avisos no aparelho (push) ----------
  const vapid = (await a.call('GET', '/push/public-key')).body;
  check('Push: chave pública do servidor', vapid && /^[A-Za-z0-9_-]{80,90}$/.test(vapid.publicKey), JSON.stringify(vapid));
  const fakeKey = Buffer.concat([Buffer.from([4]), crypto.randomBytes(64)]);
  const deviceKeys = crypto.createECDH('prime256v1'); deviceKeys.generateKeys();
  const sub = { endpoint: 'https://fcm.googleapis.com/fcm/send/e2e-' + Date.now(),
    keys: { p256dh: deviceKeys.getPublicKey().toString('base64url'), auth: crypto.randomBytes(16).toString('base64url') } };
  check('Push: aparelho inscrito', (await a.call('POST', '/push/subscriptions', sub)).status === 200);
  check('Push: status do aparelho', (await a.call('GET', '/push/status?endpoint=' + encodeURIComponent(sub.endpoint))).body.subscribed === true);
  check('Push: endereço fora dos serviços de push recusado', (await a.call('POST', '/push/subscriptions',
    { endpoint: 'https://evil.example.com/x', keys: sub.keys })).status === 400);
  check('Push: chave inválida recusada', (await a.call('POST', '/push/subscriptions',
    { endpoint: sub.endpoint, keys: { p256dh: fakeKey.toString('base64url').slice(0, 20), auth: 'x' } })).status === 400);
  check('Push: B não vê o aparelho de A', (await b.call('GET', '/push/status?endpoint=' + encodeURIComponent(sub.endpoint))).body.subscribed === false);
  check('Push: desativar', (await a.call('POST', '/push/subscriptions/remove', { endpoint: sub.endpoint })).body.subscribed === false);

  // ---------- Verificação em duas etapas (conta B) ----------
  const mfaStatus = (await b.call('GET', '/auth/mfa')).body;
  check('MFA: recomendada ao Master e ainda desativada', mfaStatus.recommended === true && mfaStatus.enabled === false, JSON.stringify(mfaStatus));
  const mfaSetup = (await b.call('POST', '/auth/mfa/setup')).body;
  check('MFA: QR Code e chave para o aplicativo', /^otpauth:\/\/totp\//.test(mfaSetup.otpauthUri) && mfaSetup.qrCode.startsWith('data:image/svg'));
  const mfaOn = await b.call('POST', '/auth/mfa/enable', { code: totp(mfaSetup.secret) });
  check('MFA: ativação com o código do aplicativo', mfaOn.status === 200 && mfaOn.body.recoveryCodes.length === 10, JSON.stringify(mfaOn.body));
  const recovery = mfaOn.body.recoveryCodes || [];
  const b2 = await new Client('Beta2').init();
  const login2 = await b2.call('POST', '/auth/login', { email: b.email, password: 'SenhaSegura2026' }, true); await b2.csrf();
  check('MFA: login pede o código', login2.status === 200 && login2.body && login2.body.mfaRequired === true, JSON.stringify(login2.body));
  check('MFA: painel bloqueado até o código', (await b2.call('GET', '/auth/me')).status === 401 && (await b2.call('GET', '/customers')).status === 401);
  check('MFA: código errado recusado', (await b2.call('POST', '/auth/mfa/verify', { code: '000000' })).status === 400);
  check('MFA: código de recuperação libera o login', (await b2.call('POST', '/auth/mfa/verify', { code: recovery[0] })).status === 200);
  check('MFA: painel liberado após o código', (await b2.call('GET', '/auth/me')).status === 200);
  check('MFA: código de recuperação não vale duas vezes', await (async () => {
    const b3 = await new Client('Beta3').init(); await b3.login(b.email, 'SenhaSegura2026');
    return (await b3.call('POST', '/auth/mfa/verify', { code: recovery[0] })).status === 400; })());

  // ---------- Passada visual em todas as telas ----------
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  await ctx.route('https://www.google.com/maps**', r => r.fulfill({ contentType: 'text/html', body: '<body>mapa</body>' }));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error' && !/401|Failed to load resource: the server responded with a status of 40[13]/.test(m.text())) errors.push('console: ' + m.text()); });
  await page.goto(UI);
  await page.fill('input[name=email]', a.email);
  await page.fill('input[name=password]', 'SenhaSegura2026');
  await page.getByRole('button', { name: 'Entrar no sistema' }).click();
  await page.waitForSelector('.greeting');
  // Avisos de aniversário e calendário abrem depois do carregamento; fecha qualquer um antes de clicar.
  const dismissModals = async (target = page) => {
    await target.waitForLoadState('networkidle');
    // Fecha pelo nome: o último botão do aviso de aniversário é "Ver aniversariantes", que sai do painel.
    for (let i = 0; i < 4; i++) {
      const modal = target.locator('.calendar-modal');
      if (!(await modal.count())) break;
      const close = modal.getByRole('button', { name: /Fechar por hoje|Salvar meu planejamento|Revisar depois/ }).first();
      await (await close.count() ? close : modal.locator('button').last()).click();
      await target.waitForTimeout(300);
    }
  };
  await dismissModals();
  check('Painel: lembrete de verificação em duas etapas para o Master', await page.locator('.mfa-reminder').count() === 1);
  const tiles = await page.locator('.tile').evaluateAll(els => els.map(e => [...e.classList].find(c => c.startsWith('accent-'))));
  check('Painel: blocos exibidos', tiles.length >= 13, tiles.join(','));
  for (const accent of tiles) {
    const before = errors.length;
    await dismissModals();
    await page.locator('.tile.' + accent).click();
    await page.waitForTimeout(900);
    await page.screenshot({ path: OUT + '/e2e-' + accent + '.png', fullPage: true });
    check('Tela ' + accent + ' sem erros', errors.length === before, errors.slice(before).join(' | '));
    await page.locator('.back-link').first().click();
    await page.waitForSelector('.greeting');
  }
  // Relatórios por perguntas: "Quem vendeu mais?" → ranking → clique abre as vendas filtradas.
  await dismissModals();
  await page.locator('.tile.accent-indigo').click();
  await page.waitForSelector('.question');
  check('Relatórios: perguntas para o dono', await page.locator('.question').count() >= 8);
  await page.getByRole('button', { name: /Quem vendeu mais/ }).click();
  await page.waitForSelector('.ranking-row');
  await page.locator('.ranking-row').first().click();
  await page.waitForSelector('#relatorios-detalhados >> text=Limpar filtros');
  await page.waitForTimeout(800);
  await page.locator('#relatorios-detalhados').screenshot({ path: OUT + '/e2e-relatorios-vendas-filtradas.png' });
  check('Relatórios: clique no ranking filtra as vendas', (await page.locator('#relatorios-detalhados').textContent()).includes('Limpar filtros'));
  await page.getByRole('button', { name: /Quanto tenho a receber/ }).click();
  await page.waitForSelector('.indicator-detail table, .indicator-detail .empty');
  await page.waitForTimeout(600);
  await page.locator('#vendas-atualizadas').screenshot({ path: OUT + '/e2e-relatorios-a-receber.png' });
  check('Relatórios: pergunta abre o detalhe do indicador', (await page.locator('.indicator-detail h3').textContent()).includes('Vendas a receber'));
  await page.getByRole('button', { name: /Que lentes mais saem/ }).click();
  await page.waitForTimeout(900);
  await page.locator('#analise-de-vendas').screenshot({ path: OUT + '/e2e-relatorios-analise.png' });
  await page.locator('.back-link').first().click();
  await page.waitForSelector('.greeting');
  await dismissModals();
  await page.getByRole('button', { name: 'Meus dados' }).click();
  await page.getByRole('button', { name: 'Ativar verificação em duas etapas' }).click();
  await page.waitForSelector('.mfa-setup img');
  await page.locator('#mfa-title').scrollIntoViewIfNeeded();
  await page.screenshot({ path: OUT + '/e2e-mfa-ativacao.png', fullPage: true });
  check('MFA no navegador: ativação mostra QR Code', await page.locator('.mfa-setup img').count() === 1);
  // Login em duas etapas pelo navegador (conta B) e a seção em Minha conta.
  const mfaCtx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  const mp = await mfaCtx.newPage();
  mp.on('pageerror', e => errors.push('mfa: ' + e.message));
  await mp.goto(UI);
  await mp.fill('input[name=email]', b.email);
  await mp.fill('input[name=password]', 'SenhaSegura2026');
  await mp.getByRole('button', { name: 'Entrar no sistema' }).click();
  await mp.waitForSelector('text=Confirme que é você');
  await mp.screenshot({ path: OUT + '/e2e-mfa-desafio.png' });
  await mp.fill('input[autocomplete=one-time-code]', recovery[1] || '');
  await mp.getByRole('button', { name: 'Entrar', exact: true }).click();
  await mp.waitForSelector('.greeting');
  await dismissModals(mp);
  check('MFA no navegador: código de recuperação abre o painel', await mp.locator('.mfa-reminder').count() === 0);
  await mp.getByRole('button', { name: 'Meus dados' }).click();
  await mp.waitForSelector('#mfa-title');
  await mp.screenshot({ path: OUT + '/e2e-mfa-conta.png', fullPage: true });
  check('MFA no navegador: Minha conta mostra ativa', (await mp.locator('.mfa-state.on').textContent() || '').includes('Ativa'));
  await mfaCtx.close();
  const pubPage = await ctx.newPage();
  pubPage.on('pageerror', e => errors.push('site: ' + e.message));
  await pubPage.goto(UI + '/?site=' + settings.slug);
  await pubPage.waitForSelector('.site .visit-band');
  await pubPage.screenshot({ path: OUT + '/e2e-site.png', fullPage: true });
  check('Site público abre com a campanha', true);
  for (const p of ['termos', 'privacidade']) {
    await pubPage.goto(UI + '/?' + p); await pubPage.waitForSelector('.legal-page h1');
    check('Página de ' + p, (await pubPage.locator('.legal-page h1').textContent()).length > 5);
  }
  await browser.close();

  console.log(results.join('\n'));
  console.log('\nTOTAL: ' + results.length + ' verificações, ' + fail.length + ' falhas');
  if (fail.length) { console.log('\nFALHAS:\n' + fail.join('\n')); process.exit(1); }
})().catch(e => { console.log(results.join('\n')); console.error(e); process.exit(2); });
