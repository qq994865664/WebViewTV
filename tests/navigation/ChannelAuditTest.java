package com.vasthread.webviewtv.navigationtest;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.View;
import android.webkit.WebView;
import com.vasthread.webviewtv.reconstructed.*;
import org.json.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Diagnostic audit of selected public channels; does not alter the installed production app. */
public final class ChannelAuditTest extends Instrumentation {
    private MainActivity activity;
    private String requested;
    private boolean nativeProbe;
    @Override public void onCreate(Bundle args) { super.onCreate(args); requested=args==null?null:args.getString("channels"); nativeProbe=args!=null&&"1".equals(args.getString("nativeProbe")); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            java.io.InputStream in=getTargetContext().getAssets().open("china_mainland.json");
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);in.close();
            JSONArray all=new JSONArray(new String(bytes.toByteArray(),StandardCharsets.UTF_8)), selected=new JSONArray();
            Set<String> names=new LinkedHashSet<>(Arrays.asList("广东卫视（4K）","杭州生活","浙江国际","山西卫视","山西经济与科技","黄河电视台"));
            if(requested!=null)names=new LinkedHashSet<>(Arrays.asList(requested.split(",")));
            for(int i=0;i<all.length();i++) {
                JSONObject c=all.getJSONObject(i);if(!names.remove(c.getString("name")))continue;
                JSONArray sources=c.getJSONArray("urls");
                for(int s=0;s<Math.min(2,sources.length());s++)selected.put(new JSONObject().put("name",c.getString("name")+" source "+(s+1)).put("group","Audit").put("urls",new JSONArray().put(sources.get(s))));
            }
            ConfigRepository repo=new ConfigRepository(getTargetContext());repo.load();
            repo.save(new JSONObject().put("version",1).put("channelSourceUrl","").put("epg",new JSONObject().put("enabled",false)).put("playback",new JSONObject().put("sourceTimeoutSeconds",0)).put("channels",selected));
            getTargetContext().getSharedPreferences("tv",0).edit().clear().putBoolean("githubChannelSourceMigratedV1",true).commit();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            JSONArray report=new JSONArray();
            for(int i=0;i<selected.length();i++) {
                final int index=i;
                if(i>0)runOnMainSync(()->{try{Method m=MainActivity.class.getDeclaredMethod("play",int.class,int.class);m.setAccessible(true);m.invoke(activity,index,-999);}catch(Exception e){throw new RuntimeException(e);}});
                SystemClock.sleep(6000);
                if(nativeProbe && selected.getJSONObject(i).getString("name").contains("杭州")) {
                    java.io.InputStream library=getTargetContext().getAssets().open("vendor/hls.light.min.js");
                    java.io.ByteArrayOutputStream script=new java.io.ByteArrayOutputStream();
                    while((n=library.read(buffer))!=-1)script.write(buffer,0,n);library.close();
                    evaluate(new String(script.toByteArray(),StandardCharsets.UTF_8));
                    evaluate("(function(){var v=document.querySelector('video'),u=v.currentSrc||v.src;if(v.player&&v.player.dispose)v.player.dispose();else{v.pause();v.remove();}var n=document.createElement('video');n.autoplay=true;n.playsInline=true;document.body.appendChild(n);window.wvt_video=n;var h=new Hls({enableWorker:false});h.attachMedia(n);h.loadSource(u);window.auditHls=h;return u})()");
                }
                SystemClock.sleep(8000);
                JSONObject row=new JSONObject().put("channel",selected.getJSONObject(i).getString("name"));
                row.put("diagnostic",evaluate("JSON.stringify({buttons:Array.from(document.querySelectorAll('.playIco')).map(e=>e.outerHTML),source:(document.querySelector('.video-player')||{}).__vue__?.videoSource,errors:Array.from(document.querySelectorAll('video')).map(v=>v.error&&v.error.message)})"));
                row.put("page",evaluate("JSON.stringify({url:location.href,title:document.title,selected:window.wvt_video&&window.wvt_video.tagName,pre:window.wvt_preClickFinished,fit:window.wvt_isViewportFullscreen,fast:window.wvt_fastSwitchController,videos:Array.from(document.querySelectorAll('video')).map(v=>({ready:v.readyState,error:v.error&&v.error.code,paused:v.paused,time:v.currentTime,src:v.currentSrc||v.src,network:v.networkState,rect:[v.getBoundingClientRect().x,v.getBoundingClientRect().y,v.getBoundingClientRect().width,v.getBoundingClientRect().height],viewport:[document.documentElement.clientWidth,document.documentElement.clientHeight,innerWidth,innerHeight],parents:(function(){var a=[],n=v;while(n){var s=getComputedStyle(n);a.push([n.tagName,n.className,s.zoom,s.transform,s.translate,s.scale,s.position,s.getPropertyValue('inset'),s.getPropertyValue('aspect-ratio')]);n=n.parentElement}return a})(),size:[v.videoWidth,v.videoHeight]})),text:document.body.innerText.slice(0,500)})"));
                boolean[] shown={false};runOnMainSync(()->shown[0]=((View)field("playbackCover")).getVisibility()==View.GONE);row.put("revealed",shown[0]);report.put(row);
                Bundle progress=new Bundle();progress.putString("stream",row.toString()+"\n");sendStatus(0,progress);
            }
            result.putString("stream","\nAUDIT "+report.toString()+"\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    }
    private Object field(String name){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private String evaluate(String js)throws Exception{CountDownLatch latch=new CountDownLatch(1);String[] value={""};runOnMainSync(()->((WebView)field("web")).evaluateJavascript(js,s->{value[0]=s;latch.countDown();}));latch.await(5,TimeUnit.SECONDS);return value[0];}
}
