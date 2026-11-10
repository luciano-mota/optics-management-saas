/** Formulário de taxas da maquininha, adaptado da lógica do PhysioEvo. */
export const SETTLEMENT_OPTIONS=[0,1,2,14,30] as const;

export function settlementLabel(days:number):string {
  if(days===0)return 'Na hora';
  if(days===1)return 'No dia seguinte';
  return 'Em '+days+' dias';
}

export type FeeBand={from:number;to:number;fee:string};

export function defaultBands(max:number):FeeBand[]{
  return max<=6?[{from:2,to:max,fee:''}]:
    [{from:2,to:6,fee:''},{from:7,to:max,fee:''}];
}

export function normalizeBands(bands:FeeBand[],max:number):FeeBand[]{
  const result:FeeBand[]=[];
  let from=2;
  for(const band of bands){
    if(from>max)break;
    const to=Math.min(Math.max(band.to,from),max);
    result.push({from,to,fee:band.fee});from=to+1;
  }
  if(!result.length)return defaultBands(max);
  result[result.length-1]={...result[result.length-1],to:max};
  return result;
}

export function bandsFromRates(
    rates:{installments:number;saleFee:number}[],
):FeeBand[]{
  const result:FeeBand[]=[];
  for(const item of [...rates].sort((a,b)=>a.installments-b.installments)){
    const text=formatPercent(item.saleFee);
    const previous=result[result.length-1];
    if(previous&&previous.to===item.installments-1&&previous.fee===text){
      previous.to=item.installments;
    }else result.push({from:item.installments,to:item.installments,fee:text});
  }
  return result;
}

export function bandFee(bands:FeeBand[],installments:number):string {
  return bands.find(b=>installments>=b.from&&installments<=b.to)?.fee??'';
}

/** Entradas de taxas em português: aceita 2,99 e 2.99, nunca interpreta 2,999 como 2,99. */
export function parsePercent(input:string,label:string):number {
  const clean=input.trim();
  if(!/^\d{1,2}(?:[.,]\d{1,2})?$|^30(?:[.,]0{1,2})?$/.test(clean))
    throw new Error(label+': informe um percentual de 0 a 30 com até 2 casas, como 3,49.');
  const number=Number(clean.replace(',','.'));
  if(number<0||number>30||!Number.isFinite(number))
    throw new Error(label+': a taxa deve ficar entre 0 e 30%.');
  return number;
}
export function parseOptionalPercent(input:string,label:string):number {
  return parsePercent(input.trim()||'0',label);
}
export function formatPercent(n:number):string {
  return String(n).replace('.',',');
}
