export type Health = 'disconnected'|'unavailable'|'unknown'|'idle'|'waiting'|'stalled'|'live';
export function mirrorHealth(link: {connected:boolean;mirror?:boolean}, lastFrame:number|null, now:number, capable?:boolean): Health;
export function filterNotification<T extends {app:string;title:string;body:string}>(note:T, prefs:{notifications?:boolean;blockedApps?:string[];hidePreview?:boolean}): T|null;
export function isAppSuppressed(app:string, muted:string[]|null|undefined, snoozed:Record<string,number>|null|undefined, now?:number): boolean;
export function createTransferQueue(): {
 active: number|null; enqueue(id:number):void; queuedIds():number[];
 start():boolean; cancel(id:number):boolean; done(id:number):void; fail(id:number,err?:string):void;
};
