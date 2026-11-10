import { useEffect, useState } from 'react';
import { api } from '../api';
import { phoneMask } from '../phone';

export type Notify = (message: string, error?: boolean) => void;
export type ScreenProps = { notify: Notify; can?: (permission: string) => boolean };

export function useRemote<T = any>(path: string) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshKey, setRefreshKey] = useState(0);
  useEffect(() => {
    let active = true;
    setLoading(true);
    api<T>(path).then(value => {
      if (active) setData(value);
    }).catch(() => {
      if (active) setData(null);
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [path, refreshKey]);
  return { data, loading, refresh: () => setRefreshKey(value => value + 1) };
}

export function FormField({ name, title, type = 'text', required = false, defaultValue, placeholder }: {
  name: string; title: string; type?: string; required?: boolean;
  defaultValue?: string; placeholder?: string;
}) {
  return <label className="field">{title}
    <input name={name} type={type} required={required} step={type === "number" ? "any" : undefined}
      defaultValue={defaultValue} placeholder={placeholder || title} />
  </label>;
}
/** Telefone/WhatsApp com DDD e máscara; aceita colar com +55. Envia o texto formatado no formulário. */
export function PhoneField({ name, title, required = false, defaultValue = '' }: {
  name: string; title: string; required?: boolean; defaultValue?: string | null;
}) {
  const [value, setValue] = useState(phoneMask(defaultValue || ''));
  return <label className="field">{title}
    <input name={name} type="tel" inputMode="tel" autoComplete="tel" required={required}
      placeholder="(00) 00000-0000" value={value} onChange={event => setValue(phoneMask(event.target.value))}
      pattern="\(\d{2}\) \d{4,5}-\d{4}" title="Informe o telefone com DDD" />
  </label>;
}
export function getField(form: HTMLFormElement, key: string): string {
  return String(new FormData(form).get(key) || '').trim();
}
export async function perform(task: () => Promise<unknown>, notify: Notify, onSuccess?: () => void) {
  try {
    await task();
    notify('Pronto! Suas alterações foram salvas.');
    onSuccess?.();
  } catch (error) {
    notify(error instanceof Error ? error.message : 'Ocorreu um problema.', true);
  }
}
export const rowId = (row: any) => String(row.id);
