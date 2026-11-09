import { useEffect, useState } from 'react';
import { api } from '../api';

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
    <input name={name} type={type} required={required}
      defaultValue={defaultValue} placeholder={placeholder || title} />
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
