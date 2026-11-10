/** Pares de fontes gratuitas (Google Fonts) do site: títulos + texto. Mesma lista do PhysioEvo e do backend. */
export type FontsId = 'elegante' | 'classica' | 'moderna' | 'geometrica' | 'suave' | 'profissional';

export const FONTS: Record<FontsId, { label: string; heading: string; body: string; families: string[] }> = {
  elegante: { label: 'Elegante', heading: 'Cormorant Garamond', body: 'Lato',
    families: ['Cormorant+Garamond:wght@500;600;700', 'Lato:wght@400;700'] },
  classica: { label: 'Clássica', heading: 'Playfair Display', body: 'Source Sans 3',
    families: ['Playfair+Display:wght@600;700', 'Source+Sans+3:wght@400;600'] },
  moderna: { label: 'Moderna', heading: 'Montserrat', body: 'Inter',
    families: ['Montserrat:wght@700;800', 'Inter:wght@400;600'] },
  geometrica: { label: 'Geométrica', heading: 'Poppins', body: 'DM Sans',
    families: ['Poppins:wght@600;700', 'DM+Sans:wght@400;500;700'] },
  suave: { label: 'Suave', heading: 'Nunito', body: 'Nunito Sans',
    families: ['Nunito:wght@700;800', 'Nunito+Sans:wght@400;600'] },
  profissional: { label: 'Profissional', heading: 'Plus Jakarta Sans', body: 'Plus Jakarta Sans',
    families: ['Plus+Jakarta+Sans:wght@400;500;600;700'] },
};

export function resolveFonts(chosen: string | null | undefined): FontsId {
  return chosen && chosen in FONTS ? chosen as FontsId : 'profissional';
}

export function fontsUrl(id: FontsId): string {
  return `https://fonts.googleapis.com/css2?${FONTS[id].families.map(f => 'family=' + f).join('&')}&display=swap`;
}
