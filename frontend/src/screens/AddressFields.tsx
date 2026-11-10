import { useEffect, useRef, useState } from 'react';
import { LoaderCircle, MapPin } from 'lucide-react';
import {
  cepDigits, cepMask, formatAddress, lookupCep, parseAddress,
  type AddressParts, type CepAddress,
} from '../cep';

type Props = {
  /** Nome do campo oculto enviado no formulário com o endereço completo. */
  name?: string;
  legend?: string;
  required?: boolean;
  defaultValue?: string | null;
  disabled?: boolean;
  /** Para formulários controlados (ex.: Meus dados). */
  onChange?: (address: string) => void;
  /** Quando o CEP é encontrado: cidade, UF e código IBGE (ex.: calendário de feriados). */
  onLocation?: (found: CepAddress) => void;
};

/**
 * Endereço com CEP: ao digitar os 8 números, rua, bairro, cidade e UF são preenchidos
 * automaticamente. A pessoa completa o número e corrige o que vier incompleto.
 * O formulário continua recebendo um único campo de texto, compatível com a API.
 */
export function AddressFields({
  name = 'address', legend = 'Endereço', required = false, defaultValue,
  disabled = false, onChange, onLocation,
}: Props) {
  const [parts, setParts] = useState<AddressParts>(() => parseAddress(defaultValue));
  const [status, setStatus] = useState<'idle' | 'loading' | 'found' | 'missing'>('idle');
  const numberRef = useRef<HTMLInputElement>(null);
  const streetRef = useRef<HTMLInputElement>(null);
  const lastLookup = useRef('');
  const composed = formatAddress(parts);

  useEffect(() => { onChange?.(composed); }, [composed]); // eslint-disable-line react-hooks/exhaustive-deps

  const update = (key: keyof AddressParts, value: string) =>
    setParts(previous => ({ ...previous, [key]: value }));

  const search = async (value: string) => {
    const cep = cepDigits(value);
    if (cep.length !== 8 || cep === lastLookup.current) return;
    lastLookup.current = cep;
    setStatus('loading');
    const found = await lookupCep(cep);
    if (!found) {
      setStatus('missing');
      streetRef.current?.focus();
      return;
    }
    setParts(previous => ({
      ...previous,
      street: found.street || previous.street,
      district: found.district || previous.district,
      city: found.city,
      uf: found.uf,
    }));
    setStatus('found');
    onLocation?.(found);
    // CEP geral da cidade não traz rua: o foco vai para onde falta preencher.
    window.setTimeout(() => (found.street ? numberRef.current : streetRef.current)?.focus(), 0);
  };

  const streetRequired = required || Boolean(parts.cep || parts.number || parts.city);

  return <fieldset className="address-fields" disabled={disabled}>
    <legend>{legend}{!required && <span className="optional"> (opcional)</span>}</legend>
    <input type="hidden" name={name} value={composed} />
    <div className="address-grid">
      <label className="field address-cep">CEP
        <span className="input-with-icon">
          <input inputMode="numeric" autoComplete="postal-code" placeholder="00000-000"
            value={parts.cep} maxLength={9}
            onChange={event => {
              const masked = cepMask(event.target.value);
              update('cep', masked);
              if (cepDigits(masked).length === 8) void search(masked);
              else { lastLookup.current = ''; setStatus('idle'); }
            }} />
          {status === 'loading'
            ? <LoaderCircle size={16} className="spin" aria-hidden="true" />
            : <MapPin size={16} aria-hidden="true" />}
        </span>
      </label>
      <label className="field address-street">Rua / avenida
        <input ref={streetRef} autoComplete="address-line1" required={streetRequired}
          value={parts.street} onChange={event => update('street', event.target.value)} />
      </label>
      <label className="field address-number">Número
        <input ref={numberRef} inputMode="numeric" placeholder="s/n"
          value={parts.number} onChange={event => update('number', event.target.value)} />
      </label>
      <label className="field address-complement">Complemento
        <input autoComplete="address-line2" placeholder="Apto, sala, bloco"
          value={parts.complement} onChange={event => update('complement', event.target.value)} />
      </label>
      <label className="field address-district">Bairro
        <input value={parts.district} onChange={event => update('district', event.target.value)} />
      </label>
      <label className="field address-city">Cidade
        <input autoComplete="address-level2" required={streetRequired}
          value={parts.city} onChange={event => update('city', event.target.value)} />
      </label>
      <label className="field address-uf">UF
        <input autoComplete="address-level1" maxLength={2} required={streetRequired}
          value={parts.uf} onChange={event => update('uf', event.target.value.toUpperCase())} />
      </label>
    </div>
    <p className="field-hint" aria-live="polite">
      {status === 'loading' && 'Buscando endereço pelo CEP...'}
      {status === 'found' && 'Endereço encontrado. Confira e informe o número.'}
      {status === 'missing' && 'Não encontramos esse CEP. Preencha o endereço manualmente.'}
      {status === 'idle' && 'Digite o CEP para preencher o endereço automaticamente.'}
    </p>
  </fieldset>;
}
