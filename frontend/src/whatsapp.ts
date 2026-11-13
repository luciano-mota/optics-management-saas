/**
 * WhatsApp manual (decisão do dono): o botão abre o WhatsApp ou o WhatsApp Business do aparelho com a mensagem pronta,
 * e quem envia é a pessoa da ótica. Sem API paga e sem envio automático.
 */

/** Número no formato do wa.me (55 + DDD + número). Devolve null quando não dá para montar um número válido. */
export function whatsappNumber(phone: string | null | undefined): string | null {
  let digits = String(phone ?? '').replace(/\D/g, '').replace(/^0+/, '');
  if (digits.length === 12 || digits.length === 13) {
    if (!digits.startsWith('55')) return null;
  } else if (digits.length === 10 || digits.length === 11) {
    digits = '55' + digits;
  } else return null;
  return digits;
}

export function whatsappUrl(phone: string | null | undefined, message: string): string | null {
  const number = whatsappNumber(phone);
  return number ? 'https://wa.me/' + number + '?text=' + encodeURIComponent(message) : null;
}

/** Só o primeiro nome na saudação ("Olá, Maria!"). */
export function firstName(name: string | null | undefined): string {
  return String(name ?? '').trim().split(/\s+/)[0] || '';
}

export function openWhatsapp(phone: string | null | undefined, message: string, notify?: (m: string, error?: boolean) => void) {
  const url = whatsappUrl(phone, message);
  if (!url) { notify?.('Telefone do cliente incompleto. Confira o cadastro (DDD + número).', true); return; }
  window.open(url, '_blank', 'noopener,noreferrer');
}
