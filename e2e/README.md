# Teste ponta a ponta

`optics-e2e.js` percorre o sistema como um usuário real, pela API (sessão + CSRF) e pelo navegador:
operador LCM, cadastro e primeiro acesso de duas óticas, calendário (feriado estadual), catálogo,
maquininha, clientes com receita, vendas Pix/crédito/débito, entrega, recibo em PDF, relatórios
(CSV/XLSX/PDF e Vendas Atualizadas), consultas e conversão de paciente, convite, site liberado pelo
operador, pedido de visita pelo site, conversão da visita, relatórios detalhados (vendas, consultas,
recibos e pacientes), verificação em duas etapas (ativação, login com código, código de recuperação de uso
único), isolamento entre óticas e todas as telas.

O limite de cadastros por IP (5 por hora) faz a segunda rodada seguida falhar no cadastro: reinicie o backend
(o limite fica em memória) e use um banco vazio a cada rodada.

```bash
# banco vazio + backend com operador de teste
createdb optics_e2e
cd backend && mvn -DskipTests package
DB_URL=jdbc:postgresql://localhost:5432/optics_e2e DB_USERNAME=optics DB_PASSWORD=local-dev-only \
  OPTICS_EMAIL_VERIFICATION_REQUIRED=false PLATFORM_ADMIN_EMAIL=operador@lcm.test \
  PLATFORM_ADMIN_PASSWORD=SenhaForte2026x java -jar target/optics-backend-0.1.0-SNAPSHOT.jar
# outro terminal
cd frontend && npm install && npx vite --port 5173
node ../e2e/optics-e2e.js /tmp   # requer Playwright; capturas de tela vão para /tmp
```
