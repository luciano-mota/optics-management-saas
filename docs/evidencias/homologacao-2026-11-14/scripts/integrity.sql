\pset footer off
\echo '== Volume de dados de teste'
select (select count(*) from optics) optics, (select count(*) from sales) sales, (select count(*) from finance_entries) entries,
       (select count(*) from finance_settlements) settlements, (select count(*) from sale_receipts) receipts, (select count(*) from stock_movements) stock_mov;
\echo '== F1 Baixas ativas acima do valor do lançamento (deve ser 0)'
select count(*) from finance_entries e where (select coalesce(sum(amount),0) from finance_settlements s where s.entry_id=e.id and s.reversed_at is null) > e.net_amount;
\echo '== F2 Lançamento marcado quitado com saldo aberto, ou aberto com saldo zero (deve ser 0)'
select count(*) filter (where settled_on is not null and paid < net_amount) quitado_com_saldo,
       count(*) filter (where settled_on is null and paid >= net_amount and net_amount>0) aberto_sem_saldo
from (select e.*, (select coalesce(sum(amount),0) from finance_settlements s where s.entry_id=e.id and s.reversed_at is null) paid from finance_entries e) x where reversed_at is null;
\echo '== F3 Valores negativos ou zero em baixas (deve ser 0)'
select count(*) from finance_settlements where amount <= 0;
\echo '== F4 Venda x lançamentos: cobrado, taxa e líquido batem (vendas não canceladas; deve ser 0)'
select count(*) from sales s join lateral (select sum(gross_amount) g, sum(fee_amount) f, sum(net_amount) n from finance_entries e where e.sale_id=s.id) t on true
where s.status<>'CANCELLED' and (t.g<>s.charged_amount or t.f<>s.fee_amount or t.n<>s.net_amount);
\echo '== F5 Venda cancelada com lançamento ainda ativo (deve ser 0)'
select count(*) from sales s join finance_entries e on e.sale_id=s.id where s.status='CANCELLED' and e.reversed_at is null;
\echo '== F6 Venda cancelada cujo lançamento estornado ainda tem baixa ATIVA (dinheiro recebido sem devolução registrada)'
select s.optic_id, s.id sale_id, s.payment_method, st.amount, st.paid_on
from sales s join finance_entries e on e.sale_id=s.id join finance_settlements st on st.entry_id=e.id
where s.status='CANCELLED' and st.reversed_at is null order by s.created_at;
\echo '== F7 Recibo emitido para baixa estornada ou de venda cancelada'
select r.id, r.receipt_number, st.reversed_at is not null baixa_estornada, s.status from sale_receipts r join finance_settlements st on st.id=r.settlement_id join sales s on s.id=r.sale_id
where st.reversed_at is not null or s.status='CANCELLED';
\echo '== F8 Dinheiro/Pix: venda não cancelada sem baixa integral (deve ser 0)'
select count(*) from sales s join finance_entries e on e.sale_id=s.id where s.payment_method in ('PIX','DINHEIRO') and s.status<>'CANCELLED'
and (select coalesce(sum(amount),0) from finance_settlements st where st.entry_id=e.id and st.reversed_at is null) <> e.net_amount;
\echo '== T1 Dono do registro coerente entre tabelas ligadas (deve ser 0 em tudo)'
select (select count(*) from finance_settlements st join finance_entries e on e.id=st.entry_id where st.optic_id<>e.optic_id) settl_x_entry,
       (select count(*) from sale_receipts r join finance_settlements st on st.id=r.settlement_id where r.optic_id<>st.optic_id) receipt_x_settl,
       (select count(*) from stock_movements m join catalog_items c on c.id=m.item_id where m.optic_id<>c.optic_id) mov_x_item,
       (select count(*) from service_order_events v join service_orders o on o.id=v.service_order_id where v.optic_id<>o.optic_id) os_events,
       (select count(*) from sale_events v join sales s on s.id=v.sale_id where v.optic_id<>s.optic_id) sale_events;
\echo '== T2 Contas com vínculo em mais de uma ótica (informativo)'
select count(*) from (select account_id from optic_memberships group by 1 having count(*)>1) x;
\echo '== S1 Estoque: itens com saldo negativo'
select c.optic_id, c.brand, c.model, sum(m.quantity) saldo from stock_movements m join catalog_items c on c.id=m.item_id group by 1,2,3 having sum(m.quantity)<0;
\echo '== S2 Venda/cancelamento: cada venda de item controlado tem saída, e cada cancelamento a devolução'
select count(*) filter (where k='SAIDA_VENDA') saidas, count(*) filter (where k='DEVOLUCAO') devolucoes from (select kind k from stock_movements where sale_id is not null) x;
\echo '== D1 Duplicidades: chave de idempotência, número de recibo, número de OS, recibo por baixa (deve ser 0)'
select (select count(*) from (select optic_id, idempotency_key from sales where idempotency_key is not null group by 1,2 having count(*)>1) a) idem,
       (select count(*) from (select optic_id, receipt_number from sale_receipts group by 1,2 having count(*)>1) b) recibo_num,
       (select count(*) from (select optic_id, number from service_orders group by 1,2 having count(*)>1) c) os_num,
       (select count(*) from (select settlement_id from sale_receipts where settlement_id is not null group by 1 having count(*)>1) d) recibo_baixa;
\echo '== A1 Consultas sobrepostas ativas na mesma clínica (deve ser 0)'
select count(*) from optical_appointments a join optical_appointments b on a.optic_id=b.optic_id and a.clinic_id=b.clinic_id and a.id<b.id
where a.status in ('SCHEDULED','CONFIRMED') and b.status in ('SCHEDULED','CONFIRMED') and a.starts_at<b.ends_at and a.ends_at>b.starts_at;
\echo '== A2 Consultas no passado criadas como agendadas (informativo)'
select count(*) from optical_appointments where starts_at < created_at - interval '1 day';
\echo '== C1 Clientes com CPF de dígito verificador inválido ou nascimento futuro (informativo)'
select count(*) filter (where cpf ~ '^(\d)\1{10}$') cpf_repetido, count(*) filter (where birth_date > current_date) nascimento_futuro from customers;
\echo '== M1 Migrações Flyway'
select count(*) total, count(*) filter (where success) ok, max(version::int) versao from flyway_schema_history where version is not null;
\echo '== M2 Sessões e tokens'
select (select count(*) from spring_session) sessoes, (select count(*) from account_action_tokens where consumed_at is null and expires_at<now()) tokens_vencidos_nao_usados;
