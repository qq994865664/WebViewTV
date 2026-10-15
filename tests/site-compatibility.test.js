const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const read=f=>fs.readFileSync(path.join(__dirname,'../app/src/main/assets/',f),'utf8');
let checks=0;
function check(label,value){assert.ok(value,label);checks++;}
// A malformed initial adapter must not commit the injection marker or schedule a player loop.
let adapter='{',timers=0,listeners=0;
const ctx={window:{main:{getWebPageAdapter:()=>adapter,getExtra:()=>''}},document:{addEventListener(){listeners++;}},
    wvt_log(){},wvt_error(){},setTimeout(){timers++;}};
vm.createContext(ctx);
const init=read('js/platform_x5.js')+'\n'+read('js/main.js');
assert.throws(()=>vm.runInContext(init,ctx));
check('failed initialization has no success marker',!ctx.window.wvt_javascriptInjected&&timers===0);
adapter='{}';vm.runInContext(init,ctx);
check('same document can initialize on retry',ctx.window.wvt_javascriptInjected&&timers===1&&listeners===1);
vm.runInContext(init,ctx);
check('successful reinjection cannot duplicate the main loop',timers===1&&listeners===1);
function site({host='www.btime.com',text='登录后即可观看精彩节目',playing=false}={}){
    let timer,hide;const reports=[];
    const window={__wvtPlaybackToken:'1:2',main:{reportSiteIssue:(...a)=>reports.push(a)},addEventListener:(e,cb)=>hide=cb};
    const document={body:{innerText:text},querySelectorAll:()=>playing?[{paused:false,error:null,readyState:4,videoWidth:1280,currentTime:5}]:[]};
    vm.runInNewContext(read('js/site_status.js'),{window,document,location:{hostname:host},setTimeout:f=>timer=f,clearTimeout:()=>timer=null});
    return {window,document,reports,tick(){if(timer){const cb=timer;timer=null;cb();}},hide(){hide();}};
}
let f=site();check('login text must persist before reporting',f.reports.length===0);f.tick();
check('visible login restriction reports token and known code',JSON.stringify(f.reports)==='[["1:2","login-required"]]');
f.tick();check('restriction is reported once',f.reports.length===1);
for(const options of [{host:'evil.btime.com'},{text:'登录'},{playing:true}]){f=site(options);f.tick();check('unrelated host, generic login and working playback are excluded',f.reports.length===0);}
f=site();f.window.__wvtPlaybackToken='2:3';f.tick();check('old page cannot report restriction',f.reports.length===0);
f=site();f.hide();f.tick();check('page exit cancels restriction polling',f.reports.length===0);
f=site();f.document.body.innerText='';f.tick();f.document.body.innerText='登录后即可观看精彩节目';f.tick();
check('transient restriction does not accumulate across interruptions',f.reports.length===0);
const schedule='由于播出安排变更，暂不支持播放该时段内容';
f=site({host:'tv.cctv.com',text:schedule});f.tick();
check('CCTV schedule restriction has its own token-scoped issue code',JSON.stringify(f.reports)==='[["1:2","schedule-unavailable"]]');
f=site({host:'tv.cctv.com',text:'由于播出安排变更,\n 暂不支持播放\n该时段内容'});f.tick();
check('CCTV notice tolerates line breaks and comma variants',f.reports.length===1);
for(const options of [{host:'tv.cctv.com',text:schedule,playing:true},{host:'tv.cctv.com.evil.test',text:schedule},
    {host:'www.btime.com',text:schedule},{host:'tv.cctv.com',text:'暂不支持播放'},
    {host:'tv.cctv.com',text:'登录后即可观看精彩节目'}]){
    f=site(options);f.tick();check('CCTV detection excludes playing video, wrong hosts and unrelated notices',f.reports.length===0);
}
f=site({host:'tv.cctv.com',text:schedule});f.window.__wvtPlaybackToken='3:4';f.tick();
check('CCTV old document cannot label a new channel',f.reports.length===0);
f=site({host:'tv.cctv.com',text:schedule});f.document.body.innerText='';f.tick();
check('transient CCTV notice does not cause a restriction',f.reports.length===0);
const predicate=JSON.parse(read('webpage_adapter.json')).find(a=>a.url==='gdtv.cn').preClickElementCondition;
const eligible=p=>vm.runInNewContext(predicate,{e:{classList:{contains:()=>true},parentElement:p}});
check('Guangdong empty poster is not clicked',!eligible({querySelector:()=>null}));
check('Guangdong published Vue source enables playback',eligible({__vue__:{videoSource:{playUrl:'https://example.com/live.m3u8'}},querySelector:()=>null}));
check('Guangdong native source survives missing Vue internals',eligible({querySelector:()=>({currentSrc:'',getAttribute:()=>'',querySelector:()=>({getAttribute:()=> 'https://example.com/live.m3u8'})})}));
const snapshot=vm.runInNewContext(read('js/playback_diagnostic.js'),{window:{wvt_hlsRecovery:{attempted:true,failed:true}},URL,
    location:{href:'https://example.com/?auth=SECRET'},document:{querySelectorAll:()=>[{currentSrc:'https://cdn.example.com/live.m3u8?token=SECRET',readyState:0,networkState:3,error:{code:4,message:'SECRET'},paused:true,videoWidth:0,videoHeight:0,currentTime:0}]}});
check('diagnostic retains native error and recovery outcome',snapshot.videos[0].error===4&&snapshot.hlsFailed);
check('diagnostic excludes signed URLs and error text',!JSON.stringify(snapshot).includes('SECRET')&&snapshot.videos[0].host==='cdn.example.com');
console.log(`PASS ${checks} site compatibility assertions`);
