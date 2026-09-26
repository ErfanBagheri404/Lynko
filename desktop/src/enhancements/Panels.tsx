import {useState} from 'react';
import type {Lang} from '../locales';
import {mirrorHealth} from './logic.mjs';
import {text} from './strings';
import type {Preferences} from './hooks';
export function HealthPanel({lang,link,lastFrame,now,capable,refresh}:{lang:Lang;link:{connected:boolean;mirror?:boolean;control?:boolean};lastFrame:number|null;now:number;capable?:boolean;refresh:()=>void}) {
  const h=mirrorHealth(link,lastFrame,now,capable);
  return <section className="card enhancement-panel"><h2>{text(lang,'health')}</h2>
    <dl className="health-grid"><div><dt>{text(lang,'screen')}</dt><dd data-state={h}>{text(lang,h)}</dd></div>
    <div><dt>{text(lang,'lastFrame')}</dt><dd>{lastFrame===null?text(lang,'never'):<><time dateTime={new Date(lastFrame).toISOString()}>{new Date(lastFrame).toLocaleTimeString(lang)}</time> · {Math.max(0,Math.floor((now-lastFrame)/1000))} {text(lang,'seconds')}</>}</dd></div>
    <div><dt>{text(lang,'control')}</dt><dd>{!link.connected?text(lang,'disconnected'):link.control===undefined?text(lang,'unknown'):link.control?text(lang,'ready'):text(lang,'needsControl')}</dd></div></dl>
    <p className="desc">{text(lang,'healthHint')}</p><button className="btn sm ghost" disabled={!link.connected} onClick={refresh}>{text(lang,'refresh')}</button>
  </section>;
}
export function FeatureControls({lang,prefs,updatePrefs}:{lang:Lang;prefs:Preferences;updatePrefs:(patch:Partial<Preferences>)=>void}) {
  return <section className="card enhancement-panel"><h2>{text(lang,'trust')}</h2><p className="desc">{text(lang,'trustHint')}</p><div className="feature-grid">
    {(['screen','input','clipboard','files','notifications','audio'] as const).map(key=><label key={key}><input type="checkbox" checked={prefs[key]} onChange={e=>updatePrefs({[key]:e.target.checked})}/>{text(lang,key)}</label>)}
  </div></section>;
}
export function PrivacyControls({lang,prefs,updatePrefs,clear}:{lang:Lang;prefs:Preferences;updatePrefs:(patch:Partial<Preferences>)=>void;clear:()=>void}) {
  const [app,setApp]=useState('');
  const block=()=>{const id=app.trim();if(!id)return;updatePrefs({blockedApps:[...new Set([...prefs.blockedApps,id])]});setApp('');};
  return <section className="card enhancement-panel"><h2>{text(lang,'privacy')}</h2><p className="desc">{text(lang,'privacyHint')}</p>
    <label className="check-label"><input type="checkbox" checked={prefs.hidePreview} onChange={e=>updatePrefs({hidePreview:e.target.checked})}/>{text(lang,'hidePreview')}</label>
    <form className="privacy-form" onSubmit={e=>{e.preventDefault();block();}}><label className="field"><span>{text(lang,'blockedApps')}</span><input dir="auto" value={app} onChange={e=>setApp(e.target.value)} placeholder={text(lang,'appPlaceholder')}/></label><button className="btn sm" disabled={!app.trim()}>{text(lang,'block')}</button></form>
    {prefs.blockedApps.map(id=><div className="blocked-row" key={id}><span dir="auto">{id}</span><button className="btn sm ghost" onClick={()=>updatePrefs({blockedApps:prefs.blockedApps.filter(a=>a!==id)})}>{text(lang,'unblock')}</button></div>)}
    <button className="btn sm ghost" onClick={clear}>{text(lang,'clear')}</button>
  </section>;
}
