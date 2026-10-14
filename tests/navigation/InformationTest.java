package com.vasthread.webviewtv.navigationtest;
import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.View;
import com.vasthread.webviewtv.reconstructed.*;
import org.json.*;
import java.lang.reflect.*;

/** Controlled frame handoff with real wall-clock dismissal and Activity panel events. */
public final class InformationTest extends Instrumentation {
    MainActivity activity;ChannelInfoView info;TvMenuView menu;int checks;
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle out=new Bundle();try{
        ConfigRepository repo=new ConfigRepository(getTargetContext());repo.load();
        repo.save(new JSONObject("{\"version\":1,\"channelSourceUrl\":\"\",\"epg\":{\"enabled\":false},\"playback\":{\"sourceTimeoutSeconds\":0},\"channels\":[{\"name\":\"First\",\"group\":\"Test\",\"urls\":[\"http://127.0.0.1:9/a\",\"http://localhost:9/b\"]},{\"name\":\"Second\",\"group\":\"Test\",\"urls\":[\"http://127.0.0.1:9/c\"]}]}"));
        getTargetContext().getSharedPreferences("tv",0).edit().clear().putBoolean("githubChannelSourceMigratedV1",true).commit();
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();SystemClock.sleep(400);ui(()->{play(0,0);info=(ChannelInfoView)field("channelInfo");menu=(TvMenuView)field("menu");check("passive banner",!info.isClickable()&&!info.isFocusable());check("hidden during loading",!visible());});
        SystemClock.sleep(6100);ui(()->{check("long loading is separate",!visible());ready();});
        SystemClock.sleep(500);ui(()->check("first frame starts information: pending="+field("infoPending")+" ready="+field("videoReady")+" focus="+activity.hasWindowFocus()+" paused="+field("playbackPaused")+" menu="+menu.getVisibility()+" failed="+field("playbackFailed"),visible()));
        SystemClock.sleep(4200);ui(()->{check("full five seconds after long loading",visible());info.setProgramme("Updated","");});
        SystemClock.sleep(1000);ui(()->{check("EPG does not extend deadline",!visible());info.setProgramme("Later","");check("EPG cannot reopen",!visible());play(1,-999);ready();invoke("showMenu",new Class[]{boolean.class},true);});
        SystemClock.sleep(400);ui(()->{check("menu cancels delayed banner",menu.isOpen()&&!visible());invoke("showMenu",new Class[]{boolean.class},false);});
        SystemClock.sleep(400);ui(()->{check("closing menu cannot reopen",!visible());play(0,1);ready();});
        SystemClock.sleep(500);ui(()->{check("manual source shows final information",visible());check("domain follows source",text(info,"website").equals("localhost"));play(1,-999);ready();play(0,0);ready();});
        SystemClock.sleep(500);ui(()->{check("rapid changes only latest",visible()&&text(info,"name").equals("First"));invoke("openSettings",new Class[]{});check("settings hides banner",!visible());invoke("closeSettings",new Class[]{});play(1,-999);ready();invoke("openProgramme",new Class[]{});check("EPG hides pending banner",!visible());((EpgView)field("epgView")).close();play(0,0);ready();activity.onWindowFocusChanged(false);activity.onWindowFocusChanged(true);});
        SystemClock.sleep(400);ui(()->check("focus restore cannot reopen",!visible()));
        out.putString("stream","\nPASS "+checks+" information assertions\n");finish(Activity.RESULT_OK,out);
    }catch(Throwable e){out.putString("stream","\nFAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}}
    void play(int index,int source){invoke("play",new Class[]{int.class,int.class},index,source);}
    void ready(){((android.webkit.WebView)field("web")).stopLoading();try{Field buffering=MainActivity.class.getDeclaredField("buffering");buffering.setAccessible(true);buffering.setBoolean(activity,false);Field pending=MainActivity.class.getDeclaredField("pendingNavigationUrl");pending.setAccessible(true);pending.set(activity,"fixture:presented");for(String n:new String[]{"videoReady","pageCommitted","viewportFullscreen"}){Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.setBoolean(activity,true);}}catch(Exception e){throw new RuntimeException(e);}((View)field("playbackCover")).setVisibility(View.GONE);((PlaybackStatusView)field("playbackStatus")).hide();invoke("onPlaybackVisible",new Class[]{});}
    boolean visible(){return info.getVisibility()==View.VISIBLE;}
    String text(Object o,String n){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return ((android.widget.TextView)f.get(o)).getText().toString();}catch(Exception e){throw new RuntimeException(e);}}
    Object field(String n){try{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    void invoke(String n,Class[] types,Object...args){try{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
    void check(String n,boolean ok){if(!ok)throw new AssertionError(n);checks++;}
    void ui(Runnable r){Throwable[] e={null};runOnMainSync(()->{try{r.run();}catch(Throwable t){e[0]=t;}});if(e[0]!=null)throw new AssertionError(e[0]);}
}
