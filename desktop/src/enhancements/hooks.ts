import { useEffect, useRef, useState } from 'react';
import { createTransferQueue } from './logic.mjs';
export const defaults = { screen:true, input:true, clipboard:true, files:true, notifications:true, audio:true, hidePreview:false, blockedApps:[] as string[], animations:true };
export type Preferences = typeof defaults;
export function readPreferences(): Preferences {
  try {
    const raw = JSON.parse(localStorage.getItem('lynko-desktop-features') ?? '{}');
    const next = {...defaults};
    for (const key of Object.keys(defaults) as (keyof Preferences)[]) {
      if (key === 'blockedApps') next.blockedApps = Array.isArray(raw[key]) ? raw[key].filter((s:unknown) => typeof s === 'string') : [];
      else if (typeof raw[key] === 'boolean') next[key] = raw[key];
    }
    return next;
  } catch { return {...defaults}; }
}
export function usePreferences() {
  const [prefs, setPrefs] = useState(readPreferences);
  const updatePrefs = (patch:Partial<Preferences>) => {
    const next = {...readPreferences(), ...patch};
    localStorage.setItem('lynko-desktop-features', JSON.stringify(next));
    setPrefs(next);
  };
  useEffect(() => { document.documentElement.dataset.motion = prefs.animations ? 'on' : 'off'; }, [prefs.animations]);
  return {prefs, updatePrefs};
}
export interface TransferItem {
  id:number; requestId:string; path:string; deviceId:string; name:string; at:number;
  size?:number; written?:number; error?:string;
  status:'queued'|'sending'|'sent'|'failed'|'cancelled';
}
interface TransferEvent {id:string;name?:string;written?:number;total?:number;ok?:boolean;error?:string}
interface Bridge {
  invoke<T>(cmd:string,args?:Record<string,unknown>):Promise<T>;
  on<T>(event:string,handler:(event:{payload:T})=>void):Promise<()=>void>;
}
export function useTransfers(api:Bridge, connected:boolean, deviceId:string|undefined, enabled:boolean) {
  const [files,setFiles] = useState<TransferItem[]>([]);
  const rows = useRef<TransferItem[]>([]);
  const queue = useRef(createTransferQueue());
  const seq = useRef(0);
  const live = useRef({connected,deviceId,enabled});
  live.current = {connected,deviceId,enabled};
  const publish = () => setFiles([...rows.current]);
  const pump = useRef<()=>void>(()=>{});
  pump.current = () => {
    const q = queue.current;
    const next = rows.current.find(f => f.id === q.queuedIds()[0]);
    if (!live.current.connected || !live.current.enabled || next?.deviceId !== live.current.deviceId || !q.start()) return;
    const item = rows.current.find(f=>f.id===q.active)!;
    item.status='sending'; publish();
    void api.invoke('send_file', {path:item.path,requestId:item.requestId}).catch(e => {
      if (q.active !== item.id) return;
      item.status='failed'; item.error=String(e); q.fail(item.id,String(e)); publish(); pump.current();
    });
  };
  useEffect(()=>{ pump.current(); },[connected,deviceId,enabled]);
  useEffect(()=>{
    let disposed=false;
    const unsubs:(()=>void)[]=[];
    const keep = (u:()=>void) => disposed ? u() : unsubs.push(u);
    void api.on<TransferEvent>('file_progress',({payload:p})=>{
      const item=rows.current.find(f=>f.id===queue.current.active && f.requestId===p.id);
      if (!item) return;
      item.written=p.written; item.size=p.total; publish();
    }).then(keep);
    void api.on<TransferEvent>('file_done',({payload:p})=>{
      const item=rows.current.find(f=>f.id===queue.current.active && f.requestId===p.id);
      if (!item) return;
      item.status=p.ok?'sent':'failed'; item.error=p.error;
      if(p.ok) item.written=item.size ?? item.written;
      queue.current.done(item.id); publish(); pump.current();
    }).then(keep);
    return ()=>{disposed=true;unsubs.forEach(u=>u());};
  },[api]);
  const queueFile = (path:string) => {
    const current=live.current;
    if(!current.connected || !current.deviceId || !current.enabled) throw new Error('file sending disabled or no phone connected');
    const id=++seq.current;
    rows.current.push({id,requestId:crypto.randomUUID(),path,deviceId:current.deviceId,name:path.split(/[\\/]/).pop()??path,at:Date.now(),status:'queued'});
    queue.current.enqueue(id); publish(); pump.current();
  };
  const cancelFile = (id:number) => {
    if(!queue.current.cancel(id)) return;
    const item=rows.current.find(f=>f.id===id)!; item.status='cancelled';publish();pump.current();
  };
  const retryFile = (id:number) => {
    const item=rows.current.find(f=>f.id===id);
    if(!item || !['failed','cancelled'].includes(item.status)) return;
    item.status='queued';item.error=undefined;item.written=undefined;item.requestId=crypto.randomUUID();
    queue.current.enqueue(id);publish();pump.current();
  };
  return {files,queueFile,cancelFile,retryFile};
}
