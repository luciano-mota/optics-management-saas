import { useEffect, useState } from 'react';
import { BellOff, BellRing, Download, Send, Smartphone } from 'lucide-react';
import { api } from '../api';
import { canPromptInstall, currentSubscription, disablePush, enablePush, installed, isIos, onInstallChange,
  promptInstall, pushSupported } from '../pwa';

type Notify = (message: string, error?: boolean) => void;

/** Instalar o app e ligar avisos (push) neste aparelho. Cada pessoa decide no próprio celular ou computador. */
export function DeviceSettings({ notify }: { notify: Notify }) {
  const [, rerender] = useState(0);
  const [subscribed, setSubscribed] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => onInstallChange(() => rerender(n => n + 1)), []);
  useEffect(() => {
    void currentSubscription().then(async sub => {
      if (!sub) { setSubscribed(false); return; }
      const status = await api<{ subscribed: boolean }>('/push/status?endpoint=' + encodeURIComponent(sub.endpoint))
        .catch(() => ({ subscribed: false }));
      setSubscribed(status.subscribed);
    }).catch(() => setSubscribed(false));
  }, []);
  const run = (task: () => Promise<void>) => {
    setBusy(true);
    task().catch(e => notify(e instanceof Error ? e.message : 'Não foi possível concluir.', true)).finally(() => setBusy(false));
  };
  const blocked = pushSupported() && Notification.permission === 'denied';

  return <div className="surface device-settings">
    <h3>Avisos neste aparelho</h3>
    <p className="helper">Receba aviso de novo pedido de visita e de mensagens da LCM DEV TECH, mesmo com o Optics fechado.
      O aviso não mostra nome, telefone nem receita do cliente.</p>
    {!pushSupported() ? <p className="calendar-warning">{isIos() && !installed()
      ? 'No iPhone e iPad, primeiro instale o app na tela de início (veja abaixo); depois os avisos ficam disponíveis.'
      : 'Este navegador não recebe notificações. Use o Chrome, Edge, Firefox ou Safari atualizado.'}</p>
     : blocked ? <p className="calendar-warning">As notificações estão bloqueadas neste navegador. Libere em Configurações do site
       → Notificações e volte aqui.</p>
     : subscribed ? <div className="device-row">
        <p className="mfa-state on"><BellRing size={18} /> Avisos ativos neste aparelho.</p>
        <div className="filter-bar">
          <button className="outline-action" disabled={busy} onClick={() => run(async () => {
            const r = await api<{ sent: number }>('/push/test', { method: 'POST' });
            notify(r.sent > 0 ? 'Aviso de teste enviado. Ele deve aparecer em instantes.' : 'Nenhum aparelho respondeu. Ative de novo.', r.sent === 0);
          })}><Send size={15} /> Enviar aviso de teste</button>
          <button className="text-action" disabled={busy} onClick={() => run(async () => {
            await disablePush(); setSubscribed(false); notify('Avisos desativados neste aparelho.');
          })}><BellOff size={15} /> Desativar</button>
        </div>
      </div>
     : <button className="primary-action" disabled={busy || subscribed === null} onClick={() => run(async () => {
          await enablePush(); setSubscribed(true); notify('Pronto! Este aparelho vai receber os avisos.');
        })}><BellRing size={16} /> Ativar avisos neste aparelho</button>}

    <h3 className="device-install-title">Aplicativo</h3>
    {installed() ? <p className="mfa-state on"><Smartphone size={18} /> O Optics já está instalado neste aparelho.</p>
     : canPromptInstall() ? <>
        <p className="helper">Instale o Optics como aplicativo: ele abre direto, em tela cheia, com o ícone na tela inicial.</p>
        <button className="outline-action" onClick={() => void promptInstall()}><Download size={15} /> Instalar aplicativo</button>
      </>
     : isIos() ? <p className="helper">No iPhone ou iPad: toque em <strong>Compartilhar</strong> (quadrado com seta) no Safari e
        depois em <strong>Adicionar à Tela de Início</strong>.</p>
     : <p className="helper">Para instalar, use o menu do navegador (⋮) e escolha <strong>Instalar app</strong> ou
        <strong> Adicionar à tela inicial</strong>.</p>}
  </div>;
}
