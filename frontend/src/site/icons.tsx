/** Ícones de traço (24×24, cor herdada) do site da ótica; mesmo desenho dos ícones do PhysioEvo. */
const PATHS = {
  glasses: ['M3 14a3.5 3.5 0 1 0 7 0 3.5 3.5 0 1 0-7 0', 'M14 14a3.5 3.5 0 1 0 7 0 3.5 3.5 0 1 0-7 0',
    'M10 13.5c1.2-1 2.8-1 4 0', 'M3.3 12.5L5.5 6.5h2', 'M20.7 12.5L18.5 6.5h-2'],
  sun: ['M3 13a3.5 3.5 0 1 0 7 0 3.5 3.5 0 1 0-7 0z', 'M14 13a3.5 3.5 0 1 0 7 0 3.5 3.5 0 1 0-7 0z',
    'M10 12.5c1.2-1 2.8-1 4 0', 'M12 3v2', 'M6.5 5l1 1.5', 'M17.5 5l-1 1.5'],
  eye: ['M2.5 12C5 7.5 8.3 5.5 12 5.5s7 2 9.5 6.5c-2.5 4.5-5.8 6.5-9.5 6.5S5 16.5 2.5 12z', 'M12 9a3 3 0 1 0 0 6 3 3 0 1 0 0-6'],
  lens: ['M12 4c4.4 0 8 3.2 8 7.5S16.4 20 12 20s-8-4.2-8-8.5S7.6 4 12 4z', 'M7 9.5c1.5-1.8 3.2-2.6 5-2.6'],
  tool: ['M14.5 6.5a4 4 0 0 0-5 5L4 17l3 3 5.5-5.5a4 4 0 0 0 5-5L15 12l-3-3z'],
  home: ['M4 11l8-7 8 7', 'M6 9.5V20h12V9.5', 'M10 20v-5.5h4V20'],
  sparkle: ['M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z'],
  heart: ['M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  mail: ['M4 6h16v12H4z', 'M4 7l8 6 8-6'],
  whatsapp: ['M4 20l1.3-4A8 8 0 1 1 8 18.8z', 'M9 9.5c0 3 2.5 5.5 5.5 5.5l1.2-1.4-1.9-.9-.9.8a4 4 0 0 1-2.4-2.4l.8-.9-.9-1.9z'],
  pin: ['M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z', 'M12 10a2 2 0 1 0 0 .01'],
  check: ['M5 12.5l4.5 4.5L19 7.5'],
  calendar: ['M5 6h14v14H5z', 'M5 10h14', 'M9 4v4', 'M15 4v4'],
  chat: ['M5 5h14v10H9l-4 4z', 'M9 9.5h6', 'M9 12h4'],
  receipt: ['M6 3h12v18l-3-2-3 2-3-2-3 2z', 'M9 8h6', 'M9 12h6'],
  arrow: ['M5 12h14', 'M13 6l6 6-6 6']
} as const;

export type SiteIconName = keyof typeof PATHS;
export const SERVICE_ICONS: SiteIconName[] = ['glasses', 'sun', 'eye', 'lens', 'tool', 'home', 'sparkle', 'heart'];

export function SiteIcon({ name, size = 24 }: { name: SiteIconName; size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    {PATHS[name].map((d, i) => <path key={i} d={d} />)}
  </svg>;
}

/** Ícone sugerido pelo nome do serviço ("Óculos de sol" → sol, "Exame de vista" → olho...). */
export function serviceIcon(name: string): SiteIconName {
  const text = name.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  if (/sol|solar/.test(text)) return 'sun';
  if (/contato|lente de c/.test(text)) return 'lens';
  if (/exame|consulta|vista|optometr|oftalmo/.test(text)) return 'eye';
  if (/ajuste|conserto|manuten|reparo|solda/.test(text)) return 'tool';
  if (/casa|domicil|visita/.test(text)) return 'home';
  if (/infantil|crianc|kids/.test(text)) return 'heart';
  if (/oculos|armac|grau|lente/.test(text)) return 'glasses';
  return 'sparkle';
}
