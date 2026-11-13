export type Json = Record<string, unknown>;

let token: string | null = null;

export async function csrf(): Promise<string> {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error('Não foi possível iniciar sua sessão com segurança.');
  const result = await response.json() as { token: string };
  token = result.token;
  return token;
}

export async function api<T = any>(
  path: string,
  options: { method?: string; body?: unknown; isForm?: boolean } = {},
): Promise<T> {
  const method = options.method || 'GET';
  const headers: Record<string, string> = {};
  if (method !== 'GET' && method !== 'HEAD') {
    headers['X-XSRF-TOKEN'] = token || await csrf();
    if (options.body !== undefined) headers['Content-Type'] =
      options.isForm ? 'application/x-www-form-urlencoded;charset=UTF-8' : 'application/json';
  }
  const response = await fetch('/api/v1' + path, {
    method, headers, credentials: 'same-origin', cache: 'no-store',
    body: options.body === undefined ? undefined :
      options.isForm ? new URLSearchParams(options.body as Record<string, string>) :
      JSON.stringify(options.body),
  });
  if (response.status === 204) return undefined as T;
  if (!response.ok) {
    const data = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(data.error || (
      response.status === 401 ? 'Faça login para continuar.' :
      response.status === 403 ? 'Você não tem permissão para esta ação.' :
      'Não foi possível concluir a operação. Tente novamente.'
    ));
  }
  return response.json() as Promise<T>;
}

export async function fileAsBase64(file: File): Promise<string> {
  if (file.size > 4 * 1024 * 1024) throw new Error('O arquivo deve ter até 4 MB.');
  if (!['image/png', 'image/jpeg', 'image/webp', 'application/pdf'].includes(file.type))
    throw new Error('Envie PDF, PNG, JPG ou WebP.');
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
    reader.onerror = () => reject(new Error('Não foi possível ler o arquivo.'));
    reader.readAsDataURL(file);
  });
}

export const currency = (value: unknown): string =>
  Number(value || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

export const date = (value: unknown): string => {
  if (!value) return '—';
  const text = String(value).slice(0, 10);
  return /^\d{4}-\d{2}-\d{2}$/.test(text) ?
    text.split('-').reverse().join('/') : new Date(String(value)).toLocaleString('pt-BR');
};
