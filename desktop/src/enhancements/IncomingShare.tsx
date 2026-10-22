import {useEffect,useState,useCallback} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {listen} from '@tauri-apps/api/event';
import type {Lang} from '../locales';
import {text} from './strings';
import type {EnhancementKey} from './strings';
// LocalSend v2.2 prepare-upload offer (spec 4.1): sender identity + file list.
type FileItem={id:string;name:string;size:number;mime:string};
type Offer={alias:string;deviceType:string;fingerprint:string;files:FileItem[]};
// share_status payloads (enriched Rust side): receiving carries per-file +
// aggregate counts; saved carries the final path; failed/canceled end states.
type Status={status:string;name?:string;path?:string;error?:string;written?:number;size?:number;
  sessionId?:string;fileId?:string;fileIndex?:number;count?:number;totalWritten?:number;totalSize?:number;
  files?:FileItem[]};
type HistoryEntry={direction:string;name:string;files:number;size:number;time:number;dir:string};
const fmt=(n:number)=>n<1024?`${n} B`:n<1048576?`${(n/1024).toFixed(1)} KB`:n<1073741824?`${(n/1048576).toFixed(1)} MB`:`${(n/1073741824).toFixed(1)} GB`;

// Mounted at the SHELL ROOT on purpose: the offer modal and the status /
// progress layers are fixed-position floats that must work from any tab.
export function IncomingShare({lang}:{lang:Lang}) {
 const [offer,setOffer]=useState<Offer|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const [prog,setProg]=useState<{written:number;size:number;fileIndex:number;count:number;totalWritten:number;totalSize:number;files:FileItem[]}|null>(null);
 const [done,setDone]=useState<{key:'shareFinished'|'shareFinishedError'|'shareCanceled';name?:string;path?:string}|null>(null);
 const [lastBytes,setLastBytes]=useState<{b:number;t:number;speed:number}>({b:0,t:Date.now(),speed:0});
 const T=(k:EnhancementKey)=>text(lang,k);
 const open=async(path:string)=>{try{await invoke('reveal_path',{path});}catch(e){setError(String(e));}};

 useEffect(()=>{
  const a=listen<Offer>('share_offer',e=>{setOffer(e.payload);setError('');setDone(null);setProg(null);});
  const b=listen<Status>('share_status',e=>onStatus(e.payload));
  const c=listen<{connected:boolean}>('link_state',e=>{if(!e.payload.connected){setOffer(null);setProg(null);setDone(null);}});
  return()=>{void a.then(u=>u());void b.then(u=>u());void c.then(u=>u());};
 },[]);

 const onStatus=(d:Status)=>{
  if(d.status==='accepted'){setOffer(null);setProg({written:0,size:d.totalSize??0,fileIndex:0,count:d.count??0,totalWritten:0,totalSize:d.totalSize??0,files:d.files??[]});return;}
  if(d.status==='receiving'&&typeof d.totalWritten==='number'&&typeof d.totalSize==='number'&&d.totalSize>0){
    const now=Date.now();setLastBytes(p=>{const dt=now-p.t;const speed=dt>250?((d.totalWritten!-p.b)/dt*1000):p.speed;return{b:d.totalWritten!,t:now,speed};});
    setProg({written:d.written??0,size:d.size??0,fileIndex:d.fileIndex??0,count:d.count??0,totalWritten:d.totalWritten,totalSize:d.totalSize,files:d.files??[]});
    return;
  }
  if(d.status==='saved'&&d.path){setProg(null);setDone({key:'shareFinished',name:d.name,path:d.path});return;}
  if(d.status==='failed'){setProg(null);setDone({key:'shareFinishedError',name:d.name});setError(d.error??'');return;}
  if(d.status==='cancelled'||d.status==='canceledBySender'){setProg(null);setDone({key:'shareCanceled',name:d.name});return;}
 };
 const answer=async(accept:boolean)=>{
  if(!offer||busy)return;setBusy(true);
  try{await invoke('answer_transfer',{accepted:accept});setOffer(null);}catch(e){setError(String(e));}finally{setBusy(false);}
 };
 const pct=(w:number,s:number)=>s>0?Math.min(100,Math.round(w/s*100)):0;
 const files=prog?.files??[];const cur=prog?.fileIndex??0;
 const speed=lastBytes.speed>1048576?`${(lastBytes.speed/1048576).toFixed(1)} MB`:lastBytes.speed>1024?`${(lastBytes.speed/1024).toFixed(0)} KB`:`${Math.round(lastBytes.speed)} B`;

 return <>{error&&<div className="incoming-status" role="alert">{error}<button className="btn sm ghost" onClick={()=>setError('')}>{T('shareClose')}</button></div>}
 {done&&<div className="incoming-status" role="status">{T(done.key)}{done.name?<span dir="auto"> · {done.name}</span>:null}{done.path&&<button className="btn sm" onClick={()=>void open(done.path!)}>{T('shareOpenFolder')}</button>}<button className="btn sm ghost" onClick={()=>setDone(null)}>{T('shareClose')}</button></div>}
 {prog&&<div className="incoming-progress" role="status">
   <div className="incoming-progress-title">{T('shareReceiving')}</div>
   <div className="incoming-progress-grid">
    <span>{T('shareFiles').replace('{curr}',String(Math.min(cur+1,prog.count))).replace('{total}',String(prog.count))}</span>
    <span>{T('shareSize').replace('{curr}',fmt(prog.totalWritten)).replace('{total}',fmt(prog.totalSize))}</span>
    <span>{T('shareSpeed').replace('{speed}',speed)}</span>
   </div>
   <progress className="incoming-bar" max={100} value={pct(prog.totalWritten,prog.totalSize)} aria-label={T('shareReceiving')}/>
   <ul className="incoming-files">{files.map((f,i)=>
    <li key={f.id||i} dir="auto">
     <span className="incoming-file-name">{f.name}</span>
     <span className="incoming-file-size">{fmt(f.size)}</span>
     <span className="incoming-file-state">{i<cur?T('shareDone'):i===cur?T('shareSending'):T('shareQ')}</span>
    </li>)}
   </ul>
   <button className="btn sm ghost" onClick={()=>setProg(null)}>{T('shareClose')}</button>
 </div>}
 
 {offer&&<div className="modal-overlay"><section className="modal" role="dialog" aria-modal="true" aria-labelledby="incoming-title"><h2 id="incoming-title">{T('shareOfferTitle').replace('{alias}',offer.alias).replace('{n}',String(offer.files.length))}</h2><p className="desc" dir="auto">{offer.deviceType}</p><ul className="desc">{(offer.files??[]).map((f,i)=>(<li key={f.id||i} dir="auto">{f.name} — {fmt(f.size)}</li>))}</ul><p className="desc">{T('shareSaveHint')}</p>{error&&<p role="alert">{error}</p>}<div className="modal-actions"><button className="btn ghost" disabled={busy} onClick={()=>void answer(false)}>{T('shareDecline')}</button><button className="btn primary" disabled={busy} onClick={()=>void answer(true)}>{T('shareAccept')}</button></div></section></div>}
 </>;
}

// Receive-history panel (LocalSend receiveHistoryPage). Rendered INSIDE the
// Files view: it is a flow element, and at the shell root it became an extra
// flex column that squeezed the whole app.
export function ShareHistory({lang}:{lang:Lang}) {
 const [hist,setHist]=useState<HistoryEntry[]|null>(null);
 const fa=lang==='fa';
 const T=(k:EnhancementKey)=>text(lang,k);
 const open=async(path:string)=>{try{await invoke('reveal_path',{path});}catch(e){}};
 const loadHist=useCallback(()=>{void invoke<HistoryEntry[]>('history_list').then(setHist).catch(()=>setHist([]));},[]);
 useEffect(()=>{loadHist();const u=listen<{status:string}>('share_status',e=>{if(e.payload.status==='saved')loadHist();});return()=>{void u.then(x=>x());};},[loadHist]);
 return <div className="incoming-history">
  <div className="incoming-history-head">
   <h3>{T('shareHistory')}</h3>
   {!!hist?.length && <button className="btn sm ghost" onClick={()=>{void invoke('history_clear').then(()=>setHist([]));}}>{T('shareDeleteHistory')}</button>}
  </div>
  {(!hist||hist.length===0)&&<p className="incoming-empty">{T('shareEmptyHistory')}</p>}
  {hist?.map((h,i)=>(<div className="incoming-history-row" key={i}>
   <span className="incoming-history-name" dir="auto">{h.name}</span>
   <span className="incoming-history-meta">{fmt(h.size)} · {new Date(h.time*1000).toLocaleString(fa?'fa-IR':'en-US',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'})}</span>
   <button className="btn sm" onClick={()=>void open(h.dir)}>{T('shareOpenFolder')}</button>
  </div>))}
 </div>;
}
