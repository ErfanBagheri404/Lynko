import {useEffect,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {listen} from '@tauri-apps/api/event';
import type {Lang} from '../locales';
// LocalSend v2.2 prepare-upload offer (spec 4.1): sender identity + file list.
type FileItem={id:string;name:string;size:number;mime:string};
type Offer={alias:string;deviceType:string;fingerprint:string;files:FileItem[]};
export function IncomingShare({lang}:{lang:Lang}) {
 const [offer,setOffer]=useState<Offer|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[status,setStatus]=useState('');
 const fa=lang==='fa';
 useEffect(()=>{
  const a=listen<Offer>('share_offer',e=>{setOffer(e.payload);setError('');});
  const b=listen<{status:string;name?:string;path?:string;error?:string}>('share_status',e=>{const d=e.payload;setStatus(d.error??d.path??d.name??d.status);if(['saved','failed','cancelled'].includes(d.status))setOffer(null);});
  const c=listen<{connected:boolean}>('link_state',e=>{if(!e.payload.connected){setOffer(null);setStatus('');}});
  return()=>{void a.then(u=>u());void b.then(u=>u());void c.then(u=>u());};
 },[]);
 const answer=async(accept:boolean)=>{
  if(!offer||busy)return;setBusy(true);
  try {
   // The HTTP thread is parked in prepare-upload; this releases it (403 on decline).
   await invoke('answer_transfer',{accepted:accept});
   setOffer(null);
  }catch(e){setError(String(e));}finally{setBusy(false);}
 };
 return <>{status&&<div className="incoming-status" role="status">{fa?'دریافت از گوشی: ':'From phone: '}{status}<button className="btn sm ghost" onClick={()=>setStatus('')}>{fa?'بستن':'Dismiss'}</button></div>}{offer&&<div className="modal-overlay"><section className="modal" role="dialog" aria-modal="true" aria-labelledby="incoming-title"><h2 id="incoming-title">{fa?'دریافت فایل از گوشی متصل؟':'Receive files from connected phone?'}{offer.files.length>1?` (${offer.files.length})`:''}</h2><p dir="auto">{offer.alias} · {offer.deviceType}</p><ul className="desc">{(offer.files??[]).map(f=><li key={f.id} dir="auto">{f.name} — {f.size.toLocaleString()} bytes</li>)}</ul><p className="desc">{fa?'فایل‌ها در پوشه Downloads ذخیره می‌شوند. فایل موجود جایگزین نمی‌شود.':'Files are saved to your Downloads folder. Existing files will not be overwritten.'}</p>{error&&<p role="alert">{error}</p>}<div className="modal-actions"><button className="btn ghost" disabled={busy} onClick={()=>void answer(false)}>{fa?'رد کردن':'Decline'}</button><button className="btn primary" disabled={busy} onClick={()=>void answer(true)}>{fa?'پذیرش و ذخیره':'Accept and save'}</button></div></section></div>}</>;
}
