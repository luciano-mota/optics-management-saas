import { useState } from 'react';
import { api } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

export function Catalog({ notify }: ScreenProps) {
  const [kind, setKind] = useState('LENS');
  const { data, refresh } = useRemote<any[]>('/catalog?kind=' + kind);
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Lentes e armações</h2>
      <p>Organize as marcas para encontrar os produtos na hora da venda.</p></div></div>
    <div className="surface">
      <div className="filter-bar">
        <button className={kind === 'LENS' ? 'primary-action' : 'outline-action'}
          onClick={() => setKind('LENS')}>Lentes</button>
        <button className={kind === 'FRAME' ? 'primary-action' : 'outline-action'}
          onClick={() => setKind('FRAME')}>Armações</button>
      </div>
      <form className="form-grid" onSubmit={event => {
        event.preventDefault();
        const form = event.currentTarget;
        void perform(() => api('/catalog', { method: 'POST', body: {
          kind, brand: getField(form, 'brand'), model: getField(form, 'model'),
        } }), notify, () => { form.reset(); refresh(); });
      }}>
        <FormField name="brand" title="Marca" required />
        <FormField name="model" title="Modelo / tipo (opcional)" />
        <button className="primary-action form-action" type="submit">Cadastrar {kind === 'LENS' ? 'lente' : 'armação'}</button>
      </form>
    </div>
    <div className="surface"><h3>{kind === 'LENS' ? 'Lentes cadastradas' : 'Armações cadastradas'}</h3>
      {!data?.length ? <p className="empty">Nenhum produto cadastrado ainda.</p> :
        <div className="list-cards">{data.map(product => <div key={product.id} className="mini-row">
          <div><strong>{product.brand}</strong><small>{product.model || 'Sem modelo'} · {product.active ? 'Ativo' : 'Inativo'}</small></div>
          <button className="text-action" onClick={() => void perform(() =>
            api('/catalog/' + product.id + '/active', { method: 'PATCH', body: { active: !product.active } }),
            notify, refresh)}>{product.active ? 'Desativar' : 'Reativar'}</button>
        </div>)}</div>}
    </div>
  </div>;
}
