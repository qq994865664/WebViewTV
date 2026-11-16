const fs=require('fs'),vm=require('vm'),assert=require('assert');
const code=fs.readFileSync(require('path').join(__dirname,'../app/src/main/assets/js/iptv345_startup.js'),'utf8');
let checks=0;function check(ok,label){assert.ok(ok,label);checks++;}
function fixture({url='https://iptv345.com/?act=play&tid=itv&id=5',present=true,throws=false}={}){
    let now=0,id=0,hide;const timers=new Map(),calls=[];
    const select={selectedIndex:0,options:Array.from({length:6},(_,i)=>({value:'site-line-'+i,disabled:false}))};
    const video={currentTime:0,readyState:0,paused:true,ended:false,error:null,videoWidth:0,videoHeight:0};
    const window={__wvtPlaybackToken:'1:2',onLineChange:s=>{calls.push(s.selectedIndex);if(throws)throw Error('fixture');},
        addEventListener:(e,fn)=>hide=fn,removeEventListener:()=>hide=null};
    const location={href:url,hostname:new URL(url).hostname};
    const ctx={window,location,URL,Date:{now:()=>now},document:{querySelector:s=>present?(s==='select#playURL'?select:video):null},
        setTimeout:(fn,delay)=>{const n=++id;timers.set(n,{fn,due:now+delay});return n;},clearTimeout:n=>timers.delete(n)};
    vm.createContext(ctx);vm.runInContext(code,ctx);
    return {window,video,select,calls,timers,inject(){vm.runInContext(code,ctx);},arrive(){present=true;},hide(){hide&&hide();},
        advance(ms){const end=now+ms;while(timers.size){const [key,t]=[...timers].sort((a,b)=>a[1].due-b[1].due)[0];if(t.due>end)break;now=t.due;timers.delete(key);t.fn();}now=end;}};
}
let f=fixture();f.advance(4999);check(!f.calls.length,'do not switch before five-second startup grace');f.advance(1);
check(f.calls.join()==='1' && f.window.wvt_iptv345Startup.line===2,'stalled first line invokes site-owned next line');
f.advance(25000);check(f.calls.join()==='1,2,3,4,5','each alternate attempted once');
check(!f.timers.size && f.window.wvt_iptv345Startup.phase==='lines-exhausted','finite exhaustion without restart loop');
f=fixture();Object.assign(f.video,{readyState:4,paused:false,videoWidth:1920,videoHeight:1080});f.video.currentTime=1;f.advance(250);
check(f.window.wvt_iptv345Startup.phase==='playing' && !f.timers.size,'moving video clock ends recovery');
f.advance(40000);check(!f.calls.length,'working playback never interrupted');
for(const state of [{paused:true},{error:{code:3}},{ended:true},{videoWidth:0}]){
    f=fixture();Object.assign(f.video,{readyState:4,paused:false,videoWidth:1280,videoHeight:720},state);f.video.currentTime=1;f.advance(5000);
    check(f.calls.length===1,'invalid media is not successful playback');
}
f=fixture();Object.assign(f.video,{readyState:4,paused:false,videoWidth:1280,videoHeight:720});f.advance(5000);
check(f.calls.length===1,'metadata and paused clock do not disable recovery');
f=fixture();f.advance(4000);f.select.selectedIndex=2;f.advance(250);f.advance(4750);
check(!f.calls.length,'site-driven switch receives its own grace');f.advance(250);check(f.calls.join()==='3','do not race site-driven switch');
f=fixture();f.inject();check(f.timers.size===1,'reinjection cannot duplicate timers');
f.window.__wvtPlaybackToken='2:0';f.advance(6000);check(!f.calls.length&&!f.timers.size,'stale page callbacks cancelled');
f=fixture();f.hide();f.advance(10000);check(!f.calls.length&&!f.timers.size,'pagehide cancels recovery');
f=fixture();f.window.wvt_iptv345Startup.stop();f.advance(10000);check(!f.calls.length&&!f.timers.size,'native source timeout stops all internal attempts');
f=fixture({present:false});f.advance(10000);check(f.window.wvt_iptv345Startup.phase==='player-not-ready'&&!f.timers.size,'missing player discovery bounded');
f=fixture({present:false});f.advance(1000);f.arrive();f.advance(5250);check(f.calls.length===1,'late player starts its own timer');
f=fixture({throws:true});f.advance(10000);check(f.calls.length===1&&f.window.wvt_iptv345Startup.phase==='player-error'&&!f.timers.size,'broken site handler cannot loop');
f=fixture();f.select.options[1].disabled=true;f.advance(5000);check(f.calls.join()==='2','disabled lines skipped');
for(const url of ['https://iptv345.com/?act=play&tid=itv&id=37','https://iptv345.com/?act=list&tid=itv&id=5',
    'https://iptv345.com.evil.test/?act=play&tid=itv&id=5','https://example.org/']){
    f=fixture({url});check(!f.window.wvt_iptv345Startup&&!f.timers.size,'scope excludes unrelated channels/sites');
}
console.log(`PASS ${checks} IPTV345 startup assertions`);
