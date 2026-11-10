import { useRef, useState, type PointerEvent } from 'react';
import { api, currency, date, fileAsBase64 } from '../api';
import { getField, perform, useRemote, PhoneField } from './common';
import { AddressFields } from './AddressFields';
import type { ScreenProps } from './common';

type Receipt={id:string;receipt_number:number;sale_id:string;finance_entry_id:string;
  payment_gross:number;paid_on:string;issued_at:string;
  customer_name:string;customer_phone:string|null};
type Payment={payment_id:string;receipt_id:string|null;receipt_number:number|null;
  gross_amount:number;fee_amount:number;net_amount:number;settled_on:string|null;
  due_on:string|null;status:string};
type Share={url:string;shareId:string;validUntil:string};

function whatsLink(receipt:Receipt,url:string) {
  const number=String(receipt.customer_phone||'').replace(/[^0-9]/g,'');
  const phone=number.startsWith('55')?number:'55'+number;
  return 'https://wa.me/'+phone+'?text='+encodeURIComponent(
    'Olá, '+receipt.customer_name+'! Segue o link seguro do recibo de pagamento da sua compra na ótica: '+url+
    ' (disponível por 24 horas). Obrigado pela preferência!'
  );
}

async function getPdf(id:string) {
  const res=await fetch('/api/v1/receipts/'+id+'/download',{
    credentials:'same-origin',cache:'no-store',
  });
  if(!res.ok)throw new Error('Não foi possível baixar o recibo.');
  const url=URL.createObjectURL(await res.blob());
  const link=document.createElement('a');link.href=url;
  link.download='recibo-optica-'+id.slice(0,8)+'.pdf';link.click();
  window.setTimeout(()=>URL.revokeObjectURL(url),3000);
}

function PaymentsForSale({saleId,notify,onIssued}:{
  saleId:string;notify:ScreenProps['notify'];onIssued:()=>void;
}) {
  const {data,loading,refresh}=useRemote<Payment[]>('/receipts/sales/'+saleId+'/payments');
  return <div className="inline-panel">
    <h4>Pagamentos e recibos desta venda</h4>
    {loading?<p className="helper">Buscando pagamentos...</p>:
      !data?.length?<p className="helper">Nenhum lançamento financeiro encontrado.</p>:
      <div className="list-cards">{data.map(p=><div className="mini-row" key={p.payment_id}>
        <div><strong>{currency(p.gross_amount)}</strong>
          <small>{p.settled_on?'Recebido em '+date(p.settled_on):
            'Pendente · vencimento '+date(p.due_on)}</small></div>
        {p.receipt_id?<button className="outline-action" onClick={()=>
          void getPdf(p.receipt_id!).catch(e=>notify(String(e),true))}>
          Baixar recibo Nº {p.receipt_number}</button>:
          <button className="primary-action"
            disabled={!p.settled_on||p.status==='CANCELLED'}
            onClick={()=>void perform(()=>api(
              '/receipts/payments/'+p.payment_id+'/issue',{method:'POST'}),
              notify,()=>{refresh();onIssued();})}>Emitir recibo de pagamento</button>}
      </div>)}</div>}
    <p className="helper">Cada recibo comprova somente um pagamento efetivamente recebido. Parcelas não pagas não são apresentadas como quitadas.</p>
  </div>;
}

function SignaturePad({notify,onSaved}:{
  notify:ScreenProps['notify'];onSaved:()=>void;
}) {
  const canvas=useRef<HTMLCanvasElement>(null);
  const isDrawing=useRef(false);
  const draw=(event:PointerEvent<HTMLCanvasElement>,start:boolean)=>{
    const board=canvas.current;
    const ctx=board?.getContext('2d');
    if(!ctx||!board)return;
    const area=board.getBoundingClientRect();
    const x=(event.clientX-area.left)*board.width/area.width;
    const y=(event.clientY-area.top)*board.height/area.height;
    ctx.lineWidth=3;ctx.lineJoin='round';ctx.lineCap='round';ctx.strokeStyle='#17153a';
    if(start){ctx.beginPath();ctx.moveTo(x,y);}else{ctx.lineTo(x,y);ctx.stroke();}
  };
  return <div className="inline-panel">
    <h4>Desenhar assinatura</h4>
    <p className="helper">Assine usando mouse, toque ou caneta. A assinatura será salva como imagem para os próximos recibos.</p>
    <canvas ref={canvas} width={580} height={145} role="img" aria-label="Área para desenhar assinatura"
      style={{maxWidth:'100%',height:145,border:'1px dashed var(--border-strong)',
        borderRadius:10,background:'white',touchAction:'none'}}
      onPointerDown={event=>{isDrawing.current=true;
        event.currentTarget.setPointerCapture(event.pointerId);draw(event,true);}}
      onPointerMove={event=>{if(isDrawing.current)draw(event,false);}}
      onPointerUp={()=>{isDrawing.current=false;}}
      onPointerCancel={()=>{isDrawing.current=false;}}/>
    <div className="filter-bar">
      <button className="outline-action" type="button" onClick={()=>{
        const board=canvas.current;board?.getContext('2d')?.clearRect(0,0,board.width,board.height);
      }}>Limpar</button>
      <button className="primary-action" type="button" onClick={()=>{
        const board=canvas.current;
        if(!board){notify('A assinatura não está disponível.',true);return;}
        const pixels=board.getContext('2d')?.getImageData(0,0,board.width,board.height).data;
        if(!pixels||!Array.from(pixels).some((value,i)=>i%4===3&&value>0)){
          notify('Desenhe sua assinatura antes de salvar.',true);return;
        }
        const dataBase64=board.toDataURL('image/png').split(',')[1];
        void perform(()=>api('/receipts/settings/asset',{method:'POST',
          body:{kind:'SIGNATURE',mime:'image/png',dataBase64}}),
          notify,onSaved);
      }}>Salvar assinatura desenhada</button>
    </div>
  </div>;
}

export function Receipts({notify,isMaster}:ScreenProps&{isMaster:boolean}) {
  const config=useRemote<any>('/receipts/settings');
  const sales=useRemote<any[]>('/sales');
  const receipts=useRemote<Receipt[]>('/receipts');
  const [saleId,setSaleId]=useState('');
  const [link,setLink]=useState<{receiptId:string;share:Share}|null>(null);
  const [fileBusy,setFileBusy]=useState(false);

  async function upload(kind:'LOGO'|'SIGNATURE',file:File|undefined) {
    if(!file)return;
    if(file.size>2*1024*1024){
      notify('O arquivo deve ter até 2 MB.',true);return;
    }
    setFileBusy(true);
    await perform(async()=>{
      const dataBase64=await fileAsBase64(file);
      await api('/receipts/settings/asset',{method:'POST',
        body:{kind,mime:file.type,dataBase64}});
    },notify,config.refresh);
    setFileBusy(false);
  }

  return <div className="screen-stack">
    <div className="section-title"><div><h2>Recibos e comprovantes</h2>
      <p>Documentos de pagamentos recebidos com a identidade e assinatura da ótica.</p>
    </div></div>
    {isMaster&&<div className="surface">
      <h3>Identidade da ótica para os recibos</h3>
      <form className="form-grid" key={config.data?.trade_name||'receipt-config'}
        onSubmit={event=>{
          event.preventDefault();const form=event.currentTarget;
          void perform(()=>api('/receipts/settings',{method:'PUT',body:{
            legalName:getField(form,'legalName'),taxDocument:getField(form,'taxDocument'),
            address:getField(form,'address'),contactPhone:getField(form,'contactPhone'),
            signerName:getField(form,'signerName'),signerRole:getField(form,'signerRole'),
          }}),notify,config.refresh);
        }}>
        {[
          ['legalName','Razão social',config.data?.legal_name||''],
          ['taxDocument','CNPJ',config.data?.tax_document||''],
          ['signerName','Nome do responsável pela assinatura',config.data?.signer_name||''],
          ['signerRole','Cargo do responsável',config.data?.signer_role||''],
        ].map(([name,title,value])=><label className="field" key={name}>{title}
          <input name={name} defaultValue={value}
            required={['legalName','taxDocument'].includes(name)}/>
        </label>)}
        <PhoneField name="contactPhone" title="Telefone da ótica" required defaultValue={config.data?.contact_phone||''}/>
        <AddressFields legend="Endereço que aparece no recibo" required defaultValue={config.data?.address||''}/>
        <button className="primary-action form-action">Salvar identificação</button>
      </form>
      <div className="filter-bar">
        <label className="field">Logo (PNG ou JPG)
          <input type="file" disabled={fileBusy} accept="image/png,image/jpeg"
            onChange={event=>void upload('LOGO',event.target.files?.[0])}/>
        </label>
        <label className="field">Assinatura por imagem (PNG ou JPG)
          <input type="file" disabled={fileBusy} accept="image/png,image/jpeg"
            onChange={event=>void upload('SIGNATURE',event.target.files?.[0])}/>
        </label>
      </div>
      <p className="helper">Logo cadastrada: {config.data?.has_logo?'Sim':'Não'} · Assinatura: {config.data?.has_signature?'Sim':'Não'}</p>
      <SignaturePad notify={notify} onSaved={config.refresh}/>
    </div>}
    <div className="surface"><h3>Emitir recibo de uma venda</h3>
      <p className="helper">Selecione a venda para consultar os pagamentos e emitir um recibo somente após a liquidação.</p>
      <label className="field">Venda<select value={saleId} onChange={event=>setSaleId(event.target.value)}>
        <option value="">Selecione uma venda</option>
        {sales.data?.map(s=><option key={s.id} value={s.id}>
          {s.customer_name} · {date(s.sold_on)} · {currency(s.charged_amount)}
        </option>)}
      </select></label>
      {saleId&&<PaymentsForSale saleId={saleId} notify={notify} onIssued={receipts.refresh}/>}
    </div>
    <div className="surface"><h3>Recibos emitidos</h3>
      {!receipts.data?.length?<p className="empty">Ainda não há recibos emitidos.</p>:
      <div className="table-wrap"><table><thead><tr>
        <th>Número</th><th>Cliente</th><th>Recebimento</th><th>Valor</th><th>Ações</th>
      </tr></thead><tbody>{receipts.data.map(r=><tr key={r.id}>
        <td>#{r.receipt_number}</td><td>{r.customer_name}</td>
        <td>{date(r.paid_on)}</td><td>{currency(r.payment_gross)}</td>
        <td><div className="filter-bar">
          <button className="outline-action" onClick={()=>void getPdf(r.id)
            .catch(e=>notify(String(e),true))}>Baixar PDF</button>
          <button className="outline-action" onClick={()=>void perform(
            async()=>{const share=await api<Share>('/receipts/'+r.id+'/share',{method:'POST'});
              setLink({receiptId:r.id,share});},notify)}>
            Compartilhar</button>
          </div>
          {link?.receiptId===r.id&&<div className="inline-panel">
            <p className="helper">Link temporário válido até {date(link.share.validUntil)}. Pode ser revogado.</p>
            {r.customer_phone&&<a href={whatsLink(r,link.share.url)}
              target="_blank" rel="noopener noreferrer" className="outline-action">
              Abrir WhatsApp com link
            </a>}
            <button className="text-action" onClick={()=>
              void navigator.clipboard.writeText(link.share.url).then(()=>notify('Link copiado.'))}>
              Copiar link</button>
            <button className="text-action" onClick={()=>void perform(
              ()=>api('/receipts/shares/'+link.share.shareId,{method:'DELETE'}),
              notify,()=>setLink(null))}>Revogar link</button>
          </div>}
        </td>
      </tr>)}</tbody></table></div>}
    </div>
  </div>;
}
