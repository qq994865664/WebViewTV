const fs = require('fs'), vm = require('vm'), assert = require('assert');
const code = fs.readFileSync(require('path').join(__dirname, '../app/src/main/assets/js/hls_recovery.js'), 'utf8');
let checks = 0;
function fixture({host='tv.hoolo.tv', working=false, error=null}={}) {
    let time=100, timer, pagehide, disposed=0, created=0, destroyed=0, instance;
    const video = {currentSrc:'https://live.hoolo.tv/example/live.m3u8?auth_key=site-value', paused:false,
        error, readyState:working?4:0, videoWidth:working?1280:0, currentTime:working?5:0,
        player:{dispose(){disposed++;}}};
    class Hls {
        static isSupported(){return true;}
        constructor(){created++;instance=this;this.events={};}
        on(event, callback){this.events[event]=callback;}
        attachMedia(v){this.video=v;}
        loadSource(source){this.source=source;}
        destroy(){destroyed++;}
    }
    Hls.Events={MANIFEST_PARSED:'manifest',ERROR:'error'};
    const window={Hls,addEventListener:(event,fn)=>{pagehide=fn;}};
    const replacement={style:{},play:()=>Promise.resolve()};
    vm.runInNewContext(code,{location:{hostname:host},window,Hls,Date:{now:()=>time},console:{log(){}},
        document:{querySelector:()=>video,createElement:()=>replacement,body:{appendChild(){}}},
        setTimeout:fn=>{timer=fn;return 1;},clearTimeout:()=>{timer=null;}});
    return {window,video,get instance(){return instance;},get created(){return created;},get disposed(){return disposed;},get destroyed(){return destroyed;},
        tick(ms){time+=ms;const f=timer;timer=null;if(f)f();},hide(){pagehide();},get timer(){return timer;}};
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
console.log(`PASS ${checks} HLS recovery assertions`);
