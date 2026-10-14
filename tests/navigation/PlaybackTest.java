package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;
import com.vasthread.webviewtv.reconstructed.*;
import org.json.*;
import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Real WebView, real decoded canvas video, local HTTP fixtures and native failure recovery. */
public final class PlaybackTest extends Instrumentation {
    private MainActivity activity;
    private ServerSocket server;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Thread serving = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket=server.accept()) {
                        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        String request=reader.readLine(), line;
                        if(request==null) continue;
                        while((line=reader.readLine())!=null && !line.isEmpty()) { }
                        byte[] content=html(request.split(" ")[1]).getBytes(StandardCharsets.UTF_8);
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+content.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(content); out.flush();
                    } catch(IOException ignored) { }
                }
            });
            serving.setDaemon(true); serving.start();
            ConfigRepository repo=new ConfigRepository(getTargetContext()); repo.load();
            JSONObject config=new JSONObject("{\"version\":1,\"channelSourceUrl\":\"\",\"epg\":{\"enabled\":false},\"playback\":{\"sourceTimeoutSeconds\":5},\"channels\":[]}");
            for(String path:new String[]{"live","title","container","frame","collapsed","wide"}) config.getJSONArray("channels").put(new JSONObject()
                .put("name",path).put("group","Test").put("urls",new JSONArray().put("http://127.0.0.1:"+server.getLocalPort()+"/"+path)));
            repo.save(config);
            getTargetContext().getSharedPreferences("tv",0).edit().clear().putBoolean("githubChannelSourceMigratedV1",true).commit();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitPlaying("zoomed video");
            SystemClock.sleep(450);
            ui(() -> verify("real first frame schedules information", ((View)field("channelInfo")).getVisibility()==View.VISIBLE));
            verifyViewport("zoomed ancestors fit screen");
            String oldToken=activity.new Bridge().getPlaybackToken();
            ui(() -> {
                invoke("play",new Class[]{int.class,int.class},1,-999);
                verify("new channel immediately covers old video", cover().getVisibility()==View.VISIBLE);
                verify("new cover has no old channel title", cover().getText().length()==0);
                activity.new Bridge().reportPlaybackReady(oldToken,"decoded-frame");
                activity.new Bridge().reportViewportFullscreenForToken(oldToken,1280,720,640,360);
            });
            SystemClock.sleep(700);
            ui(() -> verify("old callbacks cannot expose previous channel", cover().getVisibility()==View.VISIBLE && !(Boolean)field("videoReady")));
            ui(() -> activity.new Bridge().reportVideoLoadingStateForToken(activity.new Bridge().getPlaybackToken(), false));
            SystemClock.sleep(150);
            ui(() -> verify("play event without a frame is not ready", !(Boolean)field("videoReady")));
            ui(() -> {
                SourceFailover policy=(SourceFailover)field("sourceFailover");
                long before=policy.remaining(SystemClock.elapsedRealtime());
                activity.new Bridge().reportPlaybackStageForToken(activity.new Bridge().getPlaybackToken());
                verify("stage updates do not reset timeout",policy.remaining(SystemClock.elapsedRealtime())<=before);
                verify("one connection card replaces the information banner",((View)field("playbackStatus")).getVisibility()==View.VISIBLE && ((View)field("channelInfo")).getVisibility()==View.GONE);
            });
            SystemClock.sleep(5500);
            ui(() -> {
                verify("title-only page reaches a finite error", (Boolean)field("playbackFailed") && ((String)field("failureMessage")).contains("没有备用源"));
                verify("error stops spinner", ((View)field("progress")).getVisibility()==View.GONE);
                verify("failure shows actionable card", ((View)field("playbackStatus")).getVisibility()==View.VISIBLE && ((View)field("playbackStatus")).hasFocus());
                for(int i=0;i<10;i++) {
                    activity.new Bridge().reportVideoLoadingStateForToken(activity.new Bridge().getPlaybackToken(),true);
                    activity.new Bridge().reportVideoLoadingStateForToken(activity.new Bridge().getPlaybackToken(),false);
                }
            });
            SystemClock.sleep(1100);
            ui(() -> verify("late alternating events cannot flash loading over error", (Boolean)field("playbackFailed") && ((View)field("progress")).getVisibility()==View.GONE));
            String failedToken=activity.new Bridge().getPlaybackToken();
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            SystemClock.sleep(200);
            ui(() -> verify("remote confirm retries failed source", !failedToken.equals(activity.new Bridge().getPlaybackToken()) && !(Boolean)field("playbackFailed")));
            ui(() -> invoke("play",new Class[]{int.class,int.class},2,-999));
            awaitPlaying("container adapter finds actual media");
            verifyViewport("container video fills viewport");
            ui(() -> invoke("play",new Class[]{int.class,int.class},3,-999));
            awaitPlaying("same-origin frame playback");
            verifyViewport("frame video fills viewport");
            verify("outer iframe fills viewport", "true".equals(evaluate("(function(){var f=document.querySelector('iframe'),r=f.getBoundingClientRect();return Math.abs(r.left)<3&&Math.abs(r.top)<3&&Math.abs(r.width-document.documentElement.clientWidth)<3&&Math.abs(r.height-document.documentElement.clientHeight)<3;})()")));
            ui(() -> activity.new Bridge().reportVideoLoadingStateForToken(activity.new Bridge().getPlaybackToken(), true));
            SystemClock.sleep(100);
            ui(() -> activity.new Bridge().reportPlaybackProgress(activity.new Bridge().getPlaybackToken()));
            SystemClock.sleep(900);
            ui(() -> verify("short buffering does not flash HUD", ((View)field("hud")).getVisibility()==View.GONE));
            ui(() -> invoke("play",new Class[]{int.class,int.class},4,-999));
            awaitPlaying("playing video in collapsed wrapper");
            verifyViewport("collapsed player expands to full viewport");
            ui(() -> invoke("play",new Class[]{int.class,int.class},5,-999));
            awaitPlaying("desktop page without viewport metadata");
            verifyViewport("wide desktop page fills screen");
            verify("no horizontal overflow after fullscreen", "true".equals(evaluate("Math.abs(innerWidth-document.documentElement.clientWidth)<3")));
            result.putString("stream","\nPASS "+checks+" playback assertions\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream","\nFAIL "+android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED,result);
        } finally { try { if(server!=null)server.close(); }catch(IOException ignored){} }
    }
    private String html(String path) {
        String head="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html{zoom:1.3}body{margin:48px;zoom:1.2}.wrapper{width:300px;height:180px;transform:translate(20px,30px);overflow:hidden;zoom:1.4}video{width:280px;height:160px}iframe{width:300px;height:180px}</style></head><body>Channel title";
        if(path.equals("/title"))return head+"<div>Waiting for unavailable stream</div></body></html>";
        if(path.equals("/frame"))return head+"<div class='wrapper'><iframe src='/inner'></iframe></div></body></html>";
        if(path.equals("/collapsed"))head+="<style>.wrapper,video{height:0}</style>";
        if(path.equals("/wide"))head=head.replace("<meta name='viewport' content='width=device-width,initial-scale=1'>","")+"<style>html,body{min-width:1800px}</style>";
        return head+"<div class='wrapper' id='player'><video id='live' muted autoplay playsinline></video></div><script>"+
            (path.equals("/container")?"window.wvt_video=document.getElementById('player');":"")+
            "var c=document.createElement('canvas');c.width=640;c.height=360;var ctx=c.getContext('2d'),n=0;function frame(){ctx.fillStyle=(n++%2)?'#174562':'#425f27';ctx.fillRect(0,0,640,360);ctx.fillStyle='white';ctx.font='36px sans-serif';ctx.fillText('Frame '+n,200,180)}frame();setInterval(frame,100);var v=document.getElementById('live');v.srcObject=c.captureStream(10);v.play().catch(function(){});</script></body></html>";
    }
    private void awaitPlaying(String label) {
        long end=SystemClock.uptimeMillis()+12000; boolean[] ready={false};
        while(SystemClock.uptimeMillis()<end) { ui(() -> ready[0]=(Boolean)field("videoReady")&&cover().getVisibility()==View.GONE); if(ready[0])break;SystemClock.sleep(100); }
        if(!ready[0]) throw new AssertionError(label+" failed: "+evaluate("JSON.stringify({video:!!window.wvt_video,tag:window.wvt_video&&window.wvt_video.tagName,ready:window.wvt_video&&window.wvt_video.readyState,fit:window.wvt_isViewportFullscreen,fast:window.wvt_fastSwitchController})"));
        verify(label,ready[0]);
    }
    private void verifyViewport(String label) { verify(label,"true".equals(evaluate("(function(){var v=window.wvt_video,r=v.getBoundingClientRect(),d=v.ownerDocument.documentElement;return v.tagName==='VIDEO'&&Math.abs(r.left)<3&&Math.abs(r.top)<3&&Math.abs(r.width-d.clientWidth)<3&&Math.abs(r.height-d.clientHeight)<3;})()"))); }
    private String evaluate(String script) { CountDownLatch latch=new CountDownLatch(1);String[] value={""};ui(() -> ((WebView)field("web")).evaluateJavascript(script,s->{value[0]=s;latch.countDown();}));try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("JS evaluation timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return value[0]; }
    private TextView cover(){return (TextView)field("playbackCover");}
    private void verify(String label,boolean ok){if(!ok)throw new AssertionError(label);checks++;}
    private Object field(String name){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void invoke(String name,Class[] types,Object...args){try{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
    private void ui(Runnable r){Throwable[] error=new Throwable[1];runOnMainSync(()->{try{r.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
}
