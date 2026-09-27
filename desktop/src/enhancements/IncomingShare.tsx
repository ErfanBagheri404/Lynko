import {useEffect,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {listen} from '@tauri-apps/api/event';
import type {Lang} from '../locales';
// LocalSend v2.2 prepare-upload offer (spec 4.1): sender identity + file list.
type FileItem={id:string;name:string;size:number;mime:string};
type Offer={alias:string;deviceType:string;fingerprint:string;files:FileItem[]};
// share_status payloads: "receiving" carries written/size (live progress),
// "saved" carries the final path (Open in Explorer), the rest are one-liners.
type Status={status:string;name?:string;path?:string;error?:string;written?:number;size?:number};
const fmt=(n:number)=>n<1024?`${n} B`:n<1048576?`${(n/1024).toFixed(1)} KB`:`${(n/1048576).toFixed(1)} MB`;
export function IncomingShare({lang}:{lang:Lang}) {
 const [offer,setOffer]=useState<Offer|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[status,setStatus]=useState('');
 const [progress,setProgress]=useState<{written:number;size:number}|null>(null);
 const [saved,setSaved]=useState<{name:string;path:string}|null>(null);
 const fa=lang==='fa';
 const open=async(path:string)=>{
  try { await invoke('reveal_path',{path}); }
  catch(e){ setError(String(e)); }
 };
 useEffect(()=>{
  const a=listen<Offer>('share_offer',e=>{setOffer(e.payload);setError('');setSaved(null);setProgress(null);});
  const b=listen<Status>('share_status',e=>{
   const d=e.payload;
   // "receiving" WITH byte counts is live progress, not a status line. A
   // multi-file batch reuses it per file, so the bar restarts each time.
   if(d.status==='receiving'&&typeof d.written==='number'&&typeof d.size==='number'&&d.size>0){
    setStatus(d.name??'');setProgress({written:d.written,size:d.size});return;
   }
   if(d.status==='receiving'){setStatus(d.name??d.status);return;}
   if(d.status==='saved'&&d.path){setProgress(null);setSaved({name:d.name??'',path:d.path});return;}
   setProgress(null);setStatus(d.error??d.name??d.status);
   if(['saved','failed','cancelled'].includes(d.status))setOffer(null);
  });
  const c=listen<{connected:boolean}>('link_state',e=>{if(!e.payload.connected){setOffer(null);setStatus('');setProgress(null);}});
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
 const pct=progress&&progress.size>0?Math.min(100,Math.round(progress.written/progress.size*100)):0;
 return <>{status&&<div className="incoming-status" role="status">{fa?'دریافت از گوشی: ':'From phone: '}{status}{progress&&<><progress className="incoming-bar" max={100} value={pct} aria-label={fa?'پیشرفت انتقال':'Transfer progress'}/>{pct}% · {fmt(progress.written)} / {fmt(progress.size)}</>}<button className="btn sm ghost" onClick={()=>{setStatus('');setProgress(null);}}>{fa?'بستن':'Dismiss'}</button></div>}{saved&&<div className="incoming-status" role="status">{fa?'ذخیره شد: ':'Saved: '}<span dir="auto">{saved.name}</span> <button className="btn sm" onClick={()=>void open(saved.path)}>{fa?'نمایش در پوشه':'Open in Explorer'}</button> <button className="btn sm ghost" onClick={()=>setSaved(null)}>{fa?'بستن':'Dismiss'}</button></div>}{offer&&<div className="modal-overlay"><section className="modal" role="dialog" aria-modal="true" aria-labelledby="incoming-title"><h2 id="incoming-title">{fa?'دریافت فایل از گوشی متصل؟':'Receive files from connected phone?'}{offer.files.length>1?` (${offer.files.length})`:''}</h2><p dir="auto">{offer.alias} · {offer.deviceType}</p><ul className="desc">{(offer.files??[]).map(f=><li key={f.id} dir="auto">{f.name} — {f.size.toLocaleString()} bytes</li>)}</ul><p className="desc">{fa?'فایل‌ها در پوشه Downloads ذخیره می‌شوند. فایل موجود جایگزین نمی‌شود.':'Files are saved to your Downloads folder. Existing files will not be overwritten.'}</p>{error&&<p role="alert">{error}</p>}<div className="modal-actions"><button className="btn ghost" disabled={busy} onClick={()=>void answer(false)}>{fa?'رد کردن':'Decline'}</button><button className="btn primary" disabled={busy} onClick={()=>void answer(true)}>{fa?'پذیرش و ذخیره':'Accept and save'}</button></div></section></div>}</>;
}
