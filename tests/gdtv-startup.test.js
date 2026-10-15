const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const code=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/js/gdtv_startup.js'),'utf8');
let checks=0;
const check=(label,value)=>{assert.ok(value,label);checks++;};
function fixture(host='m.gdtv.cn'){
    let now=1000,clicks=0,eligible=true;
    const button={hidden:false,click(){clicks++;}},source={src:'',getAttribute(){return this.src;}};
    const video={currentSrc:'',getAttribute:()=>'',querySelector:()=>source,paused:true,error:null,readyState:0,videoWidth:0,currentTime:0};
    const player={__vue__:{isPlaying:false},querySelector:s=>s==='video'?video:button};
    const window={};
    vm.runInNewContext(code,{window,location:{hostname:host},Date:{now:()=>now},document:{querySelector:()=>player},
        getComputedStyle:b=>({display:b.hidden?'none':'block',visibility:'visible'}),wvt_getPreClickElement:()=>eligible?button:null});
    return {window,button,source,video,player,get clicks(){return clicks;},eligible(v){eligible=v;},step(ms=0){now+=ms;return window.wvt_confirmGdtvStart();}};
}
let f=fixture('example.com');check('other sites keep their adapter flow',!f.window.wvt_confirmGdtvStart);
f=fixture();f.eligible(false);f.step();check('empty poster is not clicked',f.clicks===0);
f.eligible(true);f.step();check('dispatching a click is not accepted as playback start',f.clicks===1&&!f.window.wvt_preClickFinished);
f.step(150);check('async Vue update has time before a retry',f.clicks===1);
f.source.src='https://cdn.example.com/live.m3u8?ticket=PRIVATE';f.player.__vue__.isPlaying=true;f.step(100);
check('site-assigned source and playing state confirm startup',f.window.wvt_preClickFinished&&f.window.wvt_gdtvStartup.confirmed&&f.clicks===1);
check('startup diagnostic never retains the signed URL',!JSON.stringify(f.window.wvt_gdtvStartup).includes('PRIVATE'));
f=fixture();f.step();f.step(700);f.step(700);f.step(700);
check('unaccepted clicks are limited to three',f.clicks===3&&!f.window.wvt_preClickFinished&&f.window.wvt_gdtvStartup.phase==='button-not-confirmed');
f.source.src='https://example.com/live.m3u8';f.button.hidden=true;f.step(2000);
check('late site acknowledgement can still succeed after retry limit',f.window.wvt_preClickFinished&&f.clicks===3);
f=fixture();f.player.__vue__.isPlaying=true;f.step();check('site state alone without a source does not confirm startup',!f.window.wvt_preClickFinished);
f=fixture();f.source.src='https://example.com/live.m3u8';f.step();check('an assigned URL alone with visible poster still requests a click',!f.window.wvt_preClickFinished&&f.clicks===1);
f=fixture();f.video.paused=false;f.video.readyState=4;f.video.videoWidth=1280;f.video.currentTime=1;f.step();
check('already playing video needs no click',f.window.wvt_preClickFinished&&f.clicks===0);
console.log(`PASS ${checks} Guangdong startup assertions`);
