// Funções puras de cor (vindas do PhysioEvo). Garantem texto legível (contraste WCAG) sobre as cores da ótica.

export type Theme = { primary: string; secondary: string; accent: string };

export const DEFAULT_THEME: Theme = { primary: '#5B4CF0', secondary: '#1E1B4B', accent: '#F472B6' };

export function isHex(value: string): boolean {
  return /^#[0-9a-fA-F]{6}$/.test(value);
}

function channel(hex: string, start: number): number {
  const v = parseInt(hex.slice(start, start + 2), 16) / 255;
  return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

/** Luminância relativa (0 = preto, 1 = branco), conforme WCAG 2.x. */
export function luminance(hex: string): number {
  return 0.2126 * channel(hex, 1) + 0.7152 * channel(hex, 3) + 0.0722 * channel(hex, 5);
}

export function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** Cor do texto (branco ou quase preto) com melhor contraste sobre o fundo informado. */
export function readableTextOn(background: string): string {
  const dark = '#111827';
  return contrastRatio(background, '#FFFFFF') >= contrastRatio(background, dark) ? '#FFFFFF' : dark;
}

/** Aplica o tema como variáveis CSS; cores inválidas caem no padrão. */
export function themeVariables(theme: Partial<Theme> | undefined): Record<string, string> {
  const safe = (value: string | undefined, fallback: string) => (value && isHex(value) ? value : fallback);
  const primary = safe(theme?.primary, DEFAULT_THEME.primary);
  const secondary = safe(theme?.secondary, DEFAULT_THEME.secondary);
  const accent = safe(theme?.accent, DEFAULT_THEME.accent);
  return {
    '--color-primary': primary,
    '--color-on-primary': readableTextOn(primary),
    '--color-secondary': secondary,
    '--color-on-secondary': readableTextOn(secondary),
    '--color-accent': accent,
    '--color-on-accent': readableTextOn(accent)
  };
}

function toRgb(hex: string): [number, number, number] {
  return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number];
}

/** Mistura duas cores; weight é quanto de b entra (0 = só a, 1 = só b). */
export function mix(a: string, b: string, weight: number): string {
  const [x, y] = [toRgb(a), toRgb(b)];
  return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * weight).toString(16).padStart(2, '0')).join('').toUpperCase();
}
