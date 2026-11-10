/**
 * Consulta de CEP no navegador. Usa o ViaCEP (traz também o código IBGE do município)
 * e, se ele falhar, a BrasilAPI. Nenhum dado pessoal é enviado: apenas o CEP.
 */
export type CepAddress = {
  cep: string;
  street: string;
  district: string;
  city: string;
  uf: string;
  ibge: string;
};

export const cepDigits = (value: string): string => value.replace(/\D/g, '').slice(0, 8);

export const cepMask = (value: string): string => {
  const digits = cepDigits(value);
  return digits.length > 5 ? digits.slice(0, 5) + '-' + digits.slice(5) : digits;
};

async function fetchJson(url: string, timeoutMs: number): Promise<any | null> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal, credentials: 'omit' });
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  } finally {
    window.clearTimeout(timer);
  }
}

const cache = new Map<string, CepAddress | null>();

/** Retorna o endereço do CEP, ou null quando não existe ou os serviços estão fora do ar. */
export async function lookupCep(value: string): Promise<CepAddress | null> {
  const cep = cepDigits(value);
  if (cep.length !== 8) return null;
  if (cache.has(cep)) return cache.get(cep) ?? null;

  let found: CepAddress | null = null;
  const viaCep = await fetchJson('https://viacep.com.br/ws/' + cep + '/json/', 6000);
  if (viaCep && !viaCep.erro) {
    found = {
      cep,
      street: String(viaCep.logradouro || ''),
      district: String(viaCep.bairro || ''),
      city: String(viaCep.localidade || ''),
      uf: String(viaCep.uf || ''),
      ibge: String(viaCep.ibge || ''),
    };
  } else {
    const brasilApi = await fetchJson('https://brasilapi.com.br/api/cep/v1/' + cep, 6000);
    if (brasilApi && brasilApi.city) {
      found = {
        cep,
        street: String(brasilApi.street || ''),
        district: String(brasilApi.neighborhood || ''),
        city: String(brasilApi.city || ''),
        uf: String(brasilApi.state || ''),
        ibge: '',
      };
    }
  }
  cache.set(cep, found);
  return found;
}

export type AddressParts = {
  cep: string;
  street: string;
  number: string;
  complement: string;
  district: string;
  city: string;
  uf: string;
};

export const emptyAddress: AddressParts = {
  cep: '', street: '', number: '', complement: '', district: '', city: '', uf: '',
};

/** "Rua A, 10 - Sala 2 - Centro, São Luís/MA - CEP 65000-000" */
export function formatAddress(parts: AddressParts): string {
  const street = parts.street.trim();
  if (!street && !parts.city.trim()) return '';
  const first = [street, parts.number.trim() || (street && parts.city.trim() ? 's/n' : '')].filter(Boolean).join(', ');
  const place = [parts.city.trim(), parts.uf.trim().toUpperCase()].filter(Boolean).join('/');
  const segments = [first, parts.complement.trim(), [parts.district.trim(), place].filter(Boolean).join(', ')]
    .filter(Boolean);
  const cep = cepDigits(parts.cep);
  if (cep.length === 8) segments.push('CEP ' + cepMask(cep));
  return segments.join(' - ');
}

/**
 * Lê de volta um endereço salvo por formatAddress. Endereços antigos, em texto livre,
 * ficam inteiros no campo "Rua" para a pessoa revisar.
 */
export function parseAddress(text: string | null | undefined): AddressParts {
  const value = String(text || '').trim();
  if (!value) return { ...emptyAddress };
  // Cidade e bairro não contêm " - "; o complemento pode conter (ex.: "Casa - fundos").
  const pattern = /^(.+?), ([^,]+?)(?: - (.+?))? - (?:((?:(?! - )[^,])+), )?((?:(?! - )[^,/])+)\/([A-Za-z]{2})(?: - CEP (\d{5}-?\d{3}))?$/;
  const match = value.match(pattern);
  if (!match) return { ...emptyAddress, street: value };
  return {
    street: match[1],
    number: match[2] === 's/n' ? '' : match[2],
    complement: match[3] || '',
    district: match[4] || '',
    city: match[5],
    uf: match[6].toUpperCase(),
    cep: match[7] ? cepMask(match[7]) : '',
  };
}
