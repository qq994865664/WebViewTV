const fs = require('fs'), vm = require('vm'), assert = require('assert');
const code = fs.readFileSync(require('path').join(__dirname, '../app/src/main/assets/js/hls_recovery.js'), 'utf8');
let checks = 0;
function fixture({host='tv.hoolo.tv', working=false, error=null, bridge=true}={}) {
    let time=100, nextTimer=0, pagehide, disposed=0, created=0, destroyed=0, instance;
    const timers=new Map(), reports=[], issues=[];
    const video = {currentSrc:'https://live.hoolo.tv/example/live.m3u8?auth_key=site-value', paused:false,
        error, readyState:working?4:0, videoWidth:working?1280:0, currentTime:working?5:0,
        player:{dispose(){disposed++;}}};
    class Hls {
        static isSupported(){return true;}
        constructor(){created++;instance=this;this.events={};}
        on(event, callback){this.events[event]=callback;}
        attachMedia(v){this.video=v;}
        loadSource(source){this.source=source;}
        destroy(){destroyed++;if(this.events.destroying)this.events.destroying();}
    }
    Hls.Events={MANIFEST_PARSED:'manifest',MANIFEST_LOADED:'manifestLoaded',FRAG_LOADED:'fragLoaded',
        FRAG_BUFFERED:'fragBuffered',DESTROYING:'destroying',ERROR:'error'};
    const window={Hls,__wvtPlaybackToken:'7:1',addEventListener:(event,fn)=>{pagehide=fn;}};
    if(bridge)window.main={reportMediaBytes:(token,bytes)=>reports.push({token,bytes}),reportSiteIssue:(token,code)=>issues.push({token,code})};
    const replacement={style:{},play:()=>Promise.resolve()};
    vm.runInNewContext(code,{location:{hostname:host},window,Hls,Date:{now:()=>time},console:{log(){}},
        document:{querySelector:()=>video,createElement:()=>replacement,body:{appendChild(){}}},
        URL,WeakMap,setTimeout:(fn,delay)=>{const id=++nextTimer;timers.set(id,{fn,due:time+delay});return id;},
        clearTimeout:id=>timers.delete(id)});
    return {window,video,reports,issues,get instance(){return instance;},get created(){return created;},get disposed(){return disposed;},get destroyed(){return destroyed;},
        emit(event,data){instance.events[event](null,data);},
        tick(ms){time+=ms;for(const [id,t]of [...timers])if(t.due<=time){timers.delete(id);t.fn();}},
        hide(){pagehide();},get timer(){return timers.size>0;}};
}
function check(label, condition){assert.ok(condition,label);checks++;}
let f=fixture({host:'example.com'});
check('other websites are untouched', !f.window.wvt_hlsRecovery && !f.timer);
f=fixture({working:true});f.tick(6000);
check('healthy website player is preserved',f.created===0&&f.disposed===0);
f=fixture();f.tick(2000);check('initial connection gets a grace period',f.created===0);
f.tick(1000);check('stalled native playback recovers once',f.created===1&&f.disposed===1);
check('recovery keeps the exact website URL and signed query',f.instance.source===f.video.currentSrc);
check('real replacement becomes the selected media',f.window.wvt_video===f.instance.video);
f.instance.events.error(null,{fatal:false});check('nonfatal transport retry stays bounded by engine',f.destroyed===0);
f.instance.events.error(null,{fatal:true});f.tick(10000);
check('fatal recovery stops without recreating player',f.destroyed===1&&f.created===1&&!f.timer);
f=fixture({error:{code:4}});check('native error recovers immediately',f.created===1);
f.hide();check('navigation destroys recovery engine',f.destroyed===1&&!f.timer);
f=fixture();f.hide();f.tick(5000);check('navigation cancels pending recovery',f.created===0&&!f.timer);
f=fixture({error:{code:4}});
f.emit('manifestLoaded',{stats:{loaded:240}});
check('received manifest bytes report activity with the current token',f.reports.length===1&&f.reports[0].token==='7:1'&&f.reports[0].bytes===240);
const stats={loaded:1024};
f.emit('fragLoaded',{frag:{stats}});
f.emit('fragBuffered',{frag:{stats},stats});
check('fragment events are throttled rather than sending each callback',f.reports.length===1);
f.tick(500);
check('loaded and buffered events share byte accounting without double counting',f.reports.length===2&&f.reports[1].bytes===1264);
f.emit('fragLoaded',{frag:{stats}});f.emit('fragBuffered',{stats});f.tick(1000);
check('repeated completion events do not manufacture activity',f.reports.length===2&&!f.timer);
stats.loaded=2048;f.emit('fragLoaded',{frag:{stats}});
check('increased request byte count reports only the newly received bytes',f.reports[2].bytes===2288);
const partStats={loaded:128};
f.emit('fragLoaded',{frag:{stats:{loaded:99999}},part:{stats:partStats}});f.tick(500);
check('low latency fragment parts use their own LoadStats',f.reports[3].bytes===2416);
for(const invalid of [null,{}, {stats:{}},{stats:{loaded:0}},{stats:{loaded:-1}},{stats:{loaded:NaN}},
    {stats:{loaded:Infinity}},{stats:{loaded:'100'}},{stats:{loaded:20,aborted:true}}])f.emit('fragBuffered',invalid);
f.emit('error',{fatal:false,stats:{loaded:1000}});f.tick(500);
check('missing empty aborted and error stats do not count as received bytes',f.reports.length===4);
f.emit('fragLoaded',{frag:{stats:{loaded:64}}});
f.emit('fragLoaded',{frag:{stats:{loaded:32}}});
check('a second transport report is queued within the throttle interval',f.timer);
const beforeFatal=f.reports.length;
f.emit('error',{fatal:true});f.tick(1000);f.emit('fragBuffered',{stats:{loaded:500}});
check('fatal destruction cancels pending bytes and rejects late callbacks',f.reports.length===beforeFatal&&!f.timer&&f.destroyed===1);
f=fixture({error:{code:4}});f.emit('manifestLoaded',{stats:{loaded:50}});f.emit('fragLoaded',{frag:{stats:{loaded:200}}});
f.hide();f.tick(1000);f.emit('fragLoaded',{frag:{stats:{loaded:500}}});
check('page exit cancels pending reporting and ignores late transport events',f.reports.length===1&&!f.timer&&f.destroyed===1);
f=fixture({error:{code:4}});f.emit('manifestLoaded',{stats:{loaded:50}});f.emit('fragLoaded',{frag:{stats:{loaded:200}}});
f.window.__wvtPlaybackToken='8:1';f.tick(1000);f.emit('fragLoaded',{frag:{stats:{loaded:500}}});
check('a changed playback token invalidates transport reports from the old document',f.reports.length===1&&!f.timer);
f=fixture({error:{code:4},bridge:false});f.emit('manifestLoaded',{stats:{loaded:50}});f.emit('fragLoaded',{frag:{stats:{loaded:200}}});
check('the optional Bridge does not prevent HLS recovery or queue recurring timers',f.created===1&&f.reports.length===0&&!f.timer);
f=fixture({error:{code:4}});f.emit('manifestLoaded',{stats:{loaded:50}});f.emit('fragLoaded',{frag:{stats:{loaded:200}}});
f.instance.destroy();f.tick(1000);f.emit('fragLoaded',{frag:{stats:{loaded:500}}});
check('external HLS destruction clears pending reporting',f.reports.length===1&&!f.timer&&f.destroyed===1);
f=fixture({host:'m.gdtv.cn',error:{code:4}});
check('Guangdong unsupported native HLS gets one compatibility attempt',f.created===1&&f.window.wvt_hlsRecovery.attempted);
f.emit('error',{fatal:true});f.tick(10000);
check('Guangdong fatal recovery is reported and never loops',f.created===1&&f.window.wvt_hlsRecovery.failed);
f=fixture({host:'m.gdtv.cn',working:true});f.video.currentTime=10;f.tick(5000);
f.video.readyState=0;f.video.error={code:2};f.tick(5000);
check('a previously working Guangdong player is never replaced on a later stall',f.created===0&&!f.timer);
f=fixture({host:'m.gdtv.cn',error:{code:1}});f.tick(6000);
check('intentional media abort does not trigger replacement',f.created===0);
f=fixture({host:'m.gdtv.cn'});f.video.currentSrc='blob:https://m.gdtv.cn/example';f.tick(6000);
check('existing MediaSource players are not fed back into HLS recovery',f.created===0);
f=fixture({host:'m.gdtv.cn'});f.video.currentSrc='';f.video.querySelector=()=>({src:'https://example.com/live.m3u8?ticket=keep'});f.video.error={code:4};f.tick(300);
check('Guangdong source elements work before currentSrc is populated',f.created===1&&f.instance.source==='https://example.com/live.m3u8?ticket=keep');
f=fixture({host:'m.gdtv.cn'});f.window.wvt_hlsRecovery.stop();f.tick(5000);
check('native timeout cancels a pending compatibility attempt',f.created===0&&!f.timer);
f=fixture({host:'m.gdtv.cn',error:{code:4}});f.window.wvt_hlsRecovery.stop();
check('native timeout destroys active recovery without another attempt',f.destroyed===1&&!f.timer);
f=fixture({host:'m.gdtv.cn'});f.tick(8000);
check('slow Guangdong startup retains its native player beyond three seconds',f.created===0&&f.disposed===0);
f.video.readyState=4;f.video.videoWidth=1280;f.video.currentTime=1;f.tick(500);
check('slow native startup can complete without being replaced',f.created===0&&!f.timer);
f=fixture({host:'m.gdtv.cn'});f.window.wvt_gdtvStartup={confirmed:false};f.video.error={code:4};f.tick(500);
check('compatibility recovery waits for the site to accept its start button',f.created===0);
f.window.wvt_gdtvStartup.confirmed=true;f.tick(500);
check('confirmed click and actual media error allow bounded recovery',f.created===1);
f=fixture({host:'m.gdtv.cn',error:{code:3}});
f.emit('manifestLoaded',{stats:{loaded:123}});
f.emit('error',{fatal:true,type:'networkError',details:'fragLoadError',response:{code:403,url:'https://cdn.example.com/stream?token=SECRET'},error:new Error('SECRET')});
let state=f.window.wvt_hlsRecovery;
check('native error survives replacement',state.native.error===3&&state.native.ready===0);
check('fatal HLS detail and stage survive engine destruction',state.failed&&state.stage==='failed'&&state.failedStage==='manifest-loaded'&&state.lastError.details==='fragLoadError'&&state.lastError.httpStatus===403&&state.bytes===123);
check('diagnostic strips signed URLs and error message',state.lastError.host==='cdn.example.com'&&!JSON.stringify(state).includes('SECRET'));
check('HTTP denial produces an explicit token-scoped native issue',f.issues.length===1&&f.issues[0].code==='hls-access-denied'&&f.issues[0].token==='7:1');
f.emit('error',{fatal:true,type:'mediaError',details:'bufferAppendError'});
check('late events cannot overwrite the retained cause',state.lastError.httpStatus===403&&f.issues.length===1);
for(const [data,expected]of [[{type:'networkError',details:'manifestLoadError',response:{code:0}},'hls-network'],
    [{type:'mediaError',details:'manifestIncompatibleCodecsError'},'hls-unsupported'],[{type:'mediaError',details:'bufferAppendError'},'hls-media'],
    [{type:'networkError',details:'manifestLoadError',response:{code:404}},'hls-not-found']]){
    f=fixture({host:'m.gdtv.cn',error:{code:4}});f.emit('error',{...data,fatal:true});
    check('fatal error is classified without inventing a root cause: '+expected,f.issues[0].code===expected);
}
f=fixture({error:{code:4}});f.window.__wvtPlaybackToken='new:token';f.emit('error',{fatal:true,type:'networkError'});
check('stale recovery cannot label another channel',f.issues.length===0&&!f.window.wvt_hlsRecovery.failed);
console.log(`PASS ${checks} HLS recovery assertions`);
