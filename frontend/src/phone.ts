/**
 * Telefone brasileiro com DDD: (98) 99999-0000 ou (98) 3333-0000.
 * Aceita colar com +55, espaços, pontos ou traços: o código do país é descartado.
 */
export function phoneDigits(value: string): string {
  let digits = value.replace(/\D/g, '');
  if (digits.length > 11 && digits.startsWith('55')) digits = digits.slice(2);
  if (digits.length > 11 && digits.startsWith('0')) digits = digits.replace(/^0+/, '');
  return digits.slice(0, 11);
}

export function phoneMask(value: string): string {
  const d = phoneDigits(value);
  if (d.length <= 2) return d;
  if (d.length <= 6) return '(' + d.slice(0, 2) + ') ' + d.slice(2);
  if (d.length <= 10) return '(' + d.slice(0, 2) + ') ' + d.slice(2, 6) + '-' + d.slice(6);
  return '(' + d.slice(0, 2) + ') ' + d.slice(2, 7) + '-' + d.slice(7);
}
