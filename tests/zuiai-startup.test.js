const fs=require('fs'),vm=require('vm'),assert=require('assert');
const code=fs.readFileSync(require('path').join(__dirname,'../app/src/main/assets/js/zuiai_startup.js'),'utf8');
let checks=0;
function check(value,label){assert.ok(value,label);checks++;}
function fixture({page='https://zuiai.tv/i/1453',src='https://so.zuiai.tv/m3u8/live/?ch=https%3A%2F%2Fexample.org%2Flive.m3u8',present=true}={}){
    const target=new URL(page), navigations=[],timers=new Map();let next=0,hide;
    const location={hostname:target.hostname,pathname:target.pathname,href:target.href,replace:u=>navigations.push(u)};
    const window={__wvtPlaybackToken:'1:2',addEventListener:(name,fn)=>hide=fn,removeEventListener:()=>hide=null};
    const frame={getAttribute:()=>src};
    const document={querySelector:selector=>{assert.equal(selector,'iframe#vlive');return present?frame:null;}};
    const ctx={window,document,location,URL,setTimeout:fn=>{let id=++next;timers.set(id,fn);return id;},clearTimeout:id=>timers.delete(id)};
    vm.createContext(ctx);vm.runInContext(code,ctx);
    return {window,navigations,timers,tick(){const item=[...timers][0];if(item){timers.delete(item[0]);item[1]();}},arrive(){present=true;},hide(){hide&&hide();},inject(){vm.runInContext(code,ctx);}};
}
let f=fixture();check(f.navigations.length===1,'opens site-provided cross-origin player');
check(f.navigations[0].includes('ch=https%3A%2F%2Fexample.org%2Flive.m3u8'),'query preserved');
f.inject();check(f.navigations.length===1 && f.timers.size===0,'idempotent and no redirect loop');
for(const page of ['https://evil.zuiai.tv/i/1453','https://zuiai.tv/i/999','https://so.zuiai.tv/m3u8/?ch=x']){
    f=fixture({page});check(!f.window.wvt_zuiaiStartup && !f.navigations.length,'unrelated page untouched');
}
for(const src of ['javascript:alert(1)','http://so.zuiai.tv/m3u8/live/?ch=x','https://so.zuiai.tv.evil.test/m3u8/live/?ch=x',
    'https://so.zuiai.tv/other/?ch=x','https://so.zuiai.tv/m3u8/live/','https://user:pass@so.zuiai.tv/m3u8/live/?ch=x',
    'https://so.zuiai.tv:9443/m3u8/live/?ch=x']){
    f=fixture({src});for(let i=0;i<45;i++)f.tick();
    check(!f.navigations.length && f.timers.size===0 && f.window.wvt_zuiaiStartup.phase==='player-not-found','invalid target bounded without navigation');
}
f=fixture({present:false});f.arrive();f.tick();check(f.navigations.length===1,'late iframe discovered');
f=fixture({present:false});f.window.__wvtPlaybackToken='2:0';f.arrive();f.tick();check(!f.navigations.length && !f.timers.size,'old channel cannot navigate');
f=fixture({present:false});f.hide();f.arrive();f.tick();check(!f.navigations.length && !f.timers.size,'pagehide cancels polling');
const adapters=JSON.parse(fs.readFileSync(require('path').join(__dirname,'../app/src/main/assets/webpage_adapter.json'),'utf8'));
const adapter=adapters.find(a=>a.url==='zuiai.tv');
check(adapter.ua==='MOBILE' && !adapter.needPreClick,'normal video playback after handoff');
check(vm.runInNewContext(adapter.videoElementCondition,{e:{tagName:'VIDEO',id:'video'}}),'select main player');
check(!vm.runInNewContext(adapter.videoElementCondition,{e:{tagName:'VIDEO',id:'ad-video'}}),'exclude unrelated video');
console.log(`PASS ${checks} Zuiai startup assertions`);
