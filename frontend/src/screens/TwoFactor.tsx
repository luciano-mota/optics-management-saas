import { useState } from 'react';
import { KeyRound, ShieldCheck } from 'lucide-react';
import { api } from '../api';
import { BrandLogo } from '../brand';
import { perform, useRemote } from './common';

type Notify = (message: string, error?: boolean) => void;
type Status = { enabled: boolean; enabledAt: string; recoveryRemaining: number; recommended: boolean };
type Setup = { secret: string; otpauthUri: string; qrCode: string };

function CodeInput({ value, onChange, recovery = false }: { value: string; onChange: (v: string) => void; recovery?: boolean }) {
  return <label className="field">{recovery ? 'Código do aplicativo ou de recuperação' : 'Código de 6 dígitos'}
    <input value={value} required autoComplete="one-time-code" inputMode={recovery ? 'text' : 'numeric'}
      maxLength={recovery ? 11 : 6} placeholder={recovery ? '123456 ou ABCDE-FGHJK' : '123456'}
      onChange={e => onChange(recovery ? e.target.value.toUpperCase() : e.target.value.replace(/\D/g, ''))} /></label>;
}

function RecoveryCodes({ codes, onClose }: { codes: string[]; onClose: () => void }) {
  return <div className="inline-panel">
    <h4>Guarde seus códigos de recuperação</h4>
    <p>Se perder o celular, cada código abaixo permite entrar <strong>uma única vez</strong>. Eles não serão mostrados de novo.
      Guarde em local seguro (por exemplo, impressos ou no gerenciador de senhas).</p>
    <div className="recovery-codes">{codes.map(c => <code key={c}>{c}</code>)}</div>
    <div className="filter-bar">
      <button type="button" className="outline-action" onClick={() => void navigator.clipboard?.writeText(codes.join('\n'))}>
        Copiar códigos</button>
      <button type="button" className="primary-action" onClick={onClose}>Já guardei</button>
    </div>
  </div>;
}

/** Ativar, trocar códigos de recuperação e desativar a verificação em duas etapas (Minha conta). */
export function TwoFactorSettings({ notify }: { notify: Notify }) {
  const status = useRemote<Status>('/auth/mfa');
  const [setup, setSetup] = useState<Setup | null>(null);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);
  const [disabling, setDisabling] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const reset = () => { setCode(''); setPassword(''); setSetup(null); setDisabling(false); setRegenerating(false); };
  const s = status.data;

  return <section className="surface mfa-section" aria-labelledby="mfa-title">
    <div className="section-title"><div>
      <span className="eyebrow">Segurança</span>
      <h2 id="mfa-title">Verificação em duas etapas</h2>
      <p>Além da senha, o login pede um código do aplicativo autenticador do seu celular (Google Authenticator,
        Microsoft Authenticator, Authy ou 1Password). Mesmo que alguém descubra sua senha, não consegue entrar.</p>
    </div></div>
    {!s ? <p className="empty">{status.loading ? 'Carregando...' : 'Não foi possível carregar.'}</p> :
     codes ? <RecoveryCodes codes={codes} onClose={() => { setCodes(null); status.refresh(); }} /> :
     s.enabled ? <>
      <p className="mfa-state on"><ShieldCheck size={18} /> Ativa. Códigos de recuperação disponíveis: {s.recoveryRemaining}.</p>
      {!disabling && !regenerating && <div className="filter-bar">
        <button type="button" className="outline-action" onClick={() => setRegenerating(true)}>Gerar novos códigos de recuperação</button>
        <button type="button" className="text-action" onClick={() => setDisabling(true)}>Desativar</button></div>}
      {(disabling || regenerating) && <form className="form-grid" onSubmit={e => {
        e.preventDefault();
        if (disabling) void perform(async () => {
          await api('/auth/mfa/disable', { method: 'POST', body: { password, code } });
          notify('Verificação em duas etapas desativada.'); reset(); status.refresh();
        }, notify);
        else void perform(async () => {
          const r = await api<{ recoveryCodes: string[] }>('/auth/mfa/recovery-codes', { method: 'POST', body: { code } });
          reset(); setCodes(r.recoveryCodes);
        }, notify);
      }}>
        {disabling && <label className="field">Sua senha<input type="password" required autoComplete="current-password"
          value={password} onChange={e => setPassword(e.target.value)} /></label>}
        <CodeInput value={code} onChange={setCode} recovery />
        <div className="filter-bar span-all">
          <button className={disabling ? 'outline-action' : 'primary-action'}>{disabling ? 'Desativar' : 'Gerar códigos'}</button>
          <button type="button" className="text-action" onClick={reset}>Cancelar</button></div>
      </form>}
     </> :
     !setup ? <>
      {s.recommended && <p className="calendar-warning">Recomendado para administradores da ótica e para o operador LCM DEV TECH,
        que têm acesso a todos os dados.</p>}
      <button type="button" className="primary-action" onClick={() => {
        api<Setup>('/auth/mfa/setup', { method: 'POST' }).then(setSetup)
          .catch(e => notify(e instanceof Error ? e.message : 'Não foi possível iniciar.', true));
      }}><KeyRound size={16} /> Ativar verificação em duas etapas</button>
     </> :
     <div className="mfa-setup">
      <img src={setup.qrCode} alt="QR Code para o aplicativo autenticador" width={184} height={184} />
      <div>
        <ol className="mfa-steps">
          <li>Abra o aplicativo autenticador e toque em <strong>adicionar conta</strong>.</li>
          <li>Leia o QR Code. Sem câmera? Digite a chave: <code className="mfa-secret">{setup.secret}</code></li>
          <li>Informe o código de 6 dígitos que aparece no aplicativo.</li>
        </ol>
        <form className="form-grid" onSubmit={e => {
          e.preventDefault();
          void perform(async () => {
            const r = await api<{ recoveryCodes: string[] }>('/auth/mfa/enable', { method: 'POST', body: { code } });
            reset(); setCodes(r.recoveryCodes); notify('Verificação em duas etapas ativada.');
          }, notify);
        }}>
          <CodeInput value={code} onChange={setCode} />
          <div className="filter-bar span-all"><button className="primary-action">Confirmar e ativar</button>
            <button type="button" className="text-action" onClick={reset}>Cancelar</button></div>
        </form>
      </div>
     </div>}
  </section>;
}

/** Segunda etapa do login: aparece depois da senha certa quando a conta usa verificação em duas etapas. */
export function MfaChallenge({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  return <div className="auth-layout"><div className="auth-box">
    <div className="login-brand"><BrandLogo size={40} /></div>
    <h1>Confirme que é você</h1>
    <p>Digite o código de 6 dígitos do aplicativo autenticador. Sem o celular? Use um dos códigos de recuperação.</p>
    {error && <div className="toast error" role="alert">{error}</div>}
    <form className="auth-form" onSubmit={e => {
      e.preventDefault(); setBusy(true); setError('');
      api('/auth/mfa/verify', { method: 'POST', body: { code } })
        .then(onDone)
        .catch(err => {
          const message = err instanceof Error ? err.message : 'Código inválido.';
          setError(message); setCode('');
          if (/Entre novamente/.test(message)) window.setTimeout(onCancel, 2500);
        })
        .finally(() => setBusy(false));
    }}>
      <CodeInput value={code} onChange={setCode} recovery />
      <button type="submit" className="primary-action wide" disabled={busy}>{busy ? 'Conferindo...' : 'Entrar'}</button>
    </form>
    <button className="text-action auth-switch" onClick={onCancel}>Voltar e entrar com outra conta</button>
  </div>
  <aside className="auth-aside"><ShieldCheck size={56} />
    <h2>Sua conta protegida em duas etapas.</h2>
    <p>Mesmo que alguém descubra sua senha, sem o código do seu celular não consegue entrar.</p></aside>
  </div>;
}

/** Lembrete no painel para quem administra e ainda não ativou. */
export function MfaRecommendation({ onOpen }: { onOpen: () => void }) {
  return <div className="mfa-reminder">
    <ShieldCheck size={20} />
    <p><strong>Proteja sua conta.</strong> Ative a verificação em duas etapas: além da senha, o login pede um código do celular.</p>
    <button type="button" className="outline-action" onClick={onOpen}>Ativar agora</button>
  </div>;
}
