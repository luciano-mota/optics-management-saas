import { useState } from 'react';
import { api } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

type Kind = 'LENS' | 'FRAME';
type Product = { id: string; kind: Kind; brand: string; model: string | null; category: string | null; active: boolean };
type Options = Record<Kind, Record<string, string>>;

function CategorySelect({ kind, options, value, onChange, name }: {
  kind: Kind; options: Options | null; value?: string; onChange?: (v: string) => void; name?: string;
}) {
  return <select name={name} value={value} defaultValue={value === undefined ? '' : undefined}
    onChange={e => onChange?.(e.target.value)} required={name !== undefined}>
    {name !== undefined ? <option value="" disabled>Escolha</option> : <option value="">Não informado</option>}
    {Object.entries(options?.[kind] ?? {}).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
  </select>;
}

function EditRow({ product, options, notify, onDone }: {
  product: Product; options: Options | null; notify: ScreenProps['notify']; onDone: () => void;
}) {
  const [brand, setBrand] = useState(product.brand);
  const [model, setModel] = useState(product.model ?? '');
  const [category, setCategory] = useState(product.category ?? '');
  return <form className="catalog-edit" onSubmit={e => {
    e.preventDefault();
    void perform(() => api('/catalog/' + product.id, { method: 'PATCH', body: { brand, model, category } }), notify, onDone);
  }}>
    <label className="field">Marca<input value={brand} required onChange={e => setBrand(e.target.value)} /></label>
    <label className="field">Modelo<input value={model} onChange={e => setModel(e.target.value)} /></label>
    <label className="field">{product.kind === 'LENS' ? 'Tipo' : 'Categoria'}
      <CategorySelect kind={product.kind} options={options} value={category} onChange={setCategory} /></label>
    <div className="filter-bar"><button className="primary-action">Salvar</button>
      <button type="button" className="text-action" onClick={onDone}>Cancelar</button></div>
  </form>;
}

export function Catalog({ notify }: ScreenProps) {
  const [kind, setKind] = useState<Kind>('LENS');
  const [editing, setEditing] = useState<string | null>(null);
  const { data, refresh } = useRemote<Product[]>('/catalog?kind=' + kind);
  const options = useRemote<Options>('/catalog/categories').data;
  const label = (p: Product) => p.category ? options?.[p.kind]?.[p.category] ?? p.category : null;
  const missing = (data ?? []).filter(p => p.active && !p.category).length;
  const lens = kind === 'LENS';
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Lentes e armações</h2>
      <p>Organize marcas e tipos. Eles aparecem na venda e nos relatórios (o que mais vende, por marca e por tipo).</p></div></div>
    <div className="surface">
      <div className="day-chips">
        <button type="button" className={'day-chip' + (lens ? ' active' : '')} onClick={() => { setKind('LENS'); setEditing(null); }}>Lentes</button>
        <button type="button" className={'day-chip' + (!lens ? ' active' : '')} onClick={() => { setKind('FRAME'); setEditing(null); }}>Armações</button>
      </div>
      <form key={kind} className="form-grid" onSubmit={event => {
        event.preventDefault();
        const form = event.currentTarget;
        void perform(() => api('/catalog', { method: 'POST', body: {
          kind, brand: getField(form, 'brand'), model: getField(form, 'model'), category: getField(form, 'category'),
        } }), notify, () => { form.reset(); refresh(); });
      }}>
        <FormField name="brand" title="Marca" required />
        <FormField name="model" title="Modelo (opcional)" />
        <label className="field">{lens ? 'Tipo de lente' : 'Categoria da armação'}
          <CategorySelect kind={kind} options={options} name="category" /></label>
        <button className="primary-action form-action" type="submit">Cadastrar {lens ? 'lente' : 'armação'}</button>
      </form>
    </div>
    <div className="surface"><h3>{lens ? 'Lentes cadastradas' : 'Armações cadastradas'}</h3>
      {missing > 0 && <p className="calendar-warning">{missing} {missing === 1 ? 'item está' : 'itens estão'} sem {lens ? 'tipo' : 'categoria'}.
        Toque em Editar para completar e ver os relatórios por {lens ? 'tipo de lente' : 'categoria'}.</p>}
      {!data?.length ? <p className="empty">Nenhum produto cadastrado ainda.</p> :
        <div className="list-cards">{data.map(product => editing === product.id ?
          <EditRow key={product.id} product={product} options={options} notify={notify}
            onDone={() => { setEditing(null); refresh(); }} /> :
          <div key={product.id} className="mini-row">
            <div><strong>{product.brand}{product.model ? ' · ' + product.model : ''}</strong>
              <small>{label(product) ?? <em>{lens ? 'Tipo' : 'Categoria'} não informado</em>} · {product.active ? 'Ativo' : 'Inativo'}</small></div>
            <div className="row-actions">
              <button className="text-action" onClick={() => setEditing(product.id)}>Editar</button>
              <button className="text-action" onClick={() => void perform(() =>
                api('/catalog/' + product.id + '/active', { method: 'PATCH', body: { active: !product.active } }),
                notify, refresh)}>{product.active ? 'Desativar' : 'Reativar'}</button>
            </div>
          </div>)}</div>}
    </div>
  </div>;
}
