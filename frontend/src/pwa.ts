import { api } from './api';

/** Evento de instalação do Chrome/Edge/Android, guardado para o botão "Instalar aplicativo". */
type InstallEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: string }> };
let deferred: InstallEvent | null = null;
const listeners = new Set<() => void>();

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    deferred = event as InstallEvent;
    listeners.forEach(l => l());
  });
  window.addEventListener('appinstalled', () => { deferred = null; listeners.forEach(l => l()); });
}

export function onInstallChange(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function installed() {
  return window.matchMedia?.('(display-mode: standalone)').matches || (navigator as any).standalone === true;
}

export function canPromptInstall() {
  return deferred !== null;
}

export function isIos() {
  return /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

export async function promptInstall() {
  if (!deferred) return false;
  await deferred.prompt();
  const choice = await deferred.userChoice;
  deferred = null;
  listeners.forEach(l => l());
  return choice.outcome === 'accepted';
}

export function pushSupported() {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

function keyBytes(base64url: string) {
  const padded = (base64url + '='.repeat((4 - base64url.length % 4) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(padded);
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}

async function registration() {
  const existing = await navigator.serviceWorker.getRegistration();
  return existing ?? navigator.serviceWorker.register('/sw.js');
}

export async function currentSubscription() {
  if (!pushSupported()) return null;
  const reg = await navigator.serviceWorker.getRegistration();
  return reg ? reg.pushManager.getSubscription() : null;
}

/** Pede permissão, inscreve o aparelho e envia a inscrição para a conta logada. */
export async function enablePush() {
  if (!pushSupported()) throw new Error('Este navegador não recebe notificações. No iPhone, instale o app na tela de início primeiro.');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('As notificações foram bloqueadas. Libere nas configurações do navegador.');
  const reg = await registration();
  await navigator.serviceWorker.ready;
  const { publicKey } = await api<{ publicKey: string }>('/push/public-key');
  let sub = await reg.pushManager.getSubscription();
  if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
  await api('/push/subscriptions', { method: 'POST', body: sub.toJSON() });
}

export async function disablePush() {
  const sub = await currentSubscription();
  if (!sub) return;
  await api('/push/subscriptions/remove', { method: 'POST', body: { endpoint: sub.endpoint } }).catch(() => undefined);
  await sub.unsubscribe();
}

/** Ao entrar, liga de novo a inscrição existente à conta logada (troca de usuário no mesmo aparelho). */
export async function syncPush() {
  try {
    if (!pushSupported() || Notification.permission !== 'granted') return;
    const sub = await currentSubscription();
    if (sub) await api('/push/subscriptions', { method: 'POST', body: sub.toJSON() });
  } catch { /* sem conexão ou sessão expirada */ }
}
