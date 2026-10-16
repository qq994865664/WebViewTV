package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Spinner;
import com.vasthread.webviewtv.reconstructed.*;
import org.json.*;
import java.lang.reflect.*;

/** Isolated application data, real MainActivity watchdog and simulated monotonic deadlines. */
public final class FallbackTest extends Instrumentation {
    private MainActivity activity;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            policy();
            ConfigRepository repo = new ConfigRepository(getTargetContext());
            repo.load();
            JSONObject config = new JSONObject("{\"version\":1,\"channelSourceUrl\":\"\",\"epg\":{\"enabled\":false},\"playback\":{\"sourceTimeoutSeconds\":5},\"channels\":[{\"name\":\"Fallback test\",\"group\":\"Test\",\"urls\":[\"http://127.0.0.1:9/a\",\"http://127.0.0.1:9/b\",\"http://127.0.0.1:9/c\"]}]}");
            for (int timeout : new int[]{0,5,10,15,30}) {
                config.getJSONObject("playback").put("sourceTimeoutSeconds", timeout);
                repo.save(config);
                verify("saved timeout " + timeout, repo.load().getJSONObject("playback").getInt("sourceTimeoutSeconds")==timeout);
            }
            config.getJSONObject("playback").remove("sourceTimeoutSeconds"); repo.save(config);
            verify("old config defaults to 15", repo.load().getJSONObject("playback").getInt("sourceTimeoutSeconds")==15);
            for (Object invalid : new Object[]{-1,1,20,5.5,"5",true}) {
                config.getJSONObject("playback").put("sourceTimeoutSeconds", invalid);
                boolean rejected=false;
                try { repo.save(config); } catch (java.io.IOException expected) { rejected=true; }
                verify("reject invalid timeout " + invalid, rejected);
            }
            config.getJSONObject("playback").put("sourceTimeoutSeconds",5); repo.save(config);
            getTargetContext().getSharedPreferences("tv",0).edit().clear().putBoolean("githubChannelSourceMigratedV1",true).commit();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            verify("initial source", source()==0);
            awaitSource(1,7000); verify("timeout chooses next source",source()==1);
            // Real token is accepted; an old page token must not keep a dead source alive.
            callUi(() -> activity.new Bridge().reportPlaybackProgress("stale:token"));
            awaitSource(2,7000); verify("stale progress cannot cancel timeout",source()==2);
            SystemClock.sleep(6200); verify("all failed sources stop",source()==2);
            callUi(() -> invoke("play", new Class[]{int.class,int.class},0,0));
            for(int i=0;i<7;i++) {
                callUi(() -> { try { for(String name:new String[]{"pageCommitted","viewportFullscreen"}) { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.setBoolean(activity,true); } ((android.view.View)field(activity,"playbackCover")).setVisibility(android.view.View.GONE); } catch(Exception e){throw new RuntimeException(e); } MainActivity.Bridge bridge=activity.new Bridge(); bridge.reportPlaybackProgress(bridge.getPlaybackToken()); });
                SystemClock.sleep(1000);
            }
            verify("continuous progress keeps current source",source()==0);
            callUi(() -> {
                try {
                    SourceFailover watchdog=(SourceFailover)field(activity,"sourceFailover");
                    long now=SystemClock.elapsedRealtime();
                    for (String name:new String[]{"roundStarted","lastProgress","stableSince"}) {
                        Field f=SourceFailover.class.getDeclaredField(name); f.setAccessible(true);
                        f.setLong(watchdog,name.equals("roundStarted")?now-60001:name.equals("lastProgress")?now-3001:-1);
                    }
                    MainActivity.Bridge bridge=activity.new Bridge();
                    bridge.reportPlaybackProgress(bridge.getPlaybackToken());
                    invoke("checkSourceTimeout",new Class[]{});
                    verify("sparse real playback cancels old sixty-second wait",!(Boolean)field(activity,"playbackFailed"));
                    verify("healthy playback does not switch source",(Integer)field(activity,"sourceIndex")==0);
                    verify("healthy playback keeps failure card hidden",((android.view.View)field(activity,"playbackStatus")).getVisibility()==android.view.View.GONE);
                } catch(Exception e){throw new RuntimeException(e);}
            });
            callUi(() -> invoke("showMenu",new Class[]{boolean.class},true));
            SystemClock.sleep(6200); verify("menu keeps recovery running",source()==1);
            callUi(() -> verify("automatic recovery preserves open menu", ((TvMenuView)field(activity,"menu")).isOpen()));
            callUi(() -> invoke("showMenu",new Class[]{boolean.class},false));
            SystemClock.sleep(1000); verify("closing menu does not reset interval",source()==1);
            awaitSource(2,6500); verify("continued outage switches remaining source",source()==2);
            // Setting drafts expose all choices and keep the stored selection.
            callUi(() -> {
                invoke("openSettings",new Class[]{});
                Object settings=field(activity,"settingsView");
                Spinner spinner=(Spinner)field(settings,"sourceTimeout");
                verify("settings contains off plus four choices",spinner.getCount()==5);
                verify("settings reflects persisted 5 seconds",spinner.getSelectedItemPosition()==1);
                spinner.setSelection(0);
                try { Method save=settings.getClass().getDeclaredMethod("save"); save.setAccessible(true); save.invoke(settings); }
                catch(Exception e){throw new RuntimeException(e);}
            });
            SystemClock.sleep(1500);
            verify("settings save disables timeout",repo.load().getJSONObject("playback").getInt("sourceTimeoutSeconds")==0);
            callUi(() -> invoke("showMenu",new Class[]{boolean.class},false));
            int before=source(); SystemClock.sleep(6200); verify("disabled watchdog does not switch",source()==before);
            result.putString("stream","\nPASS "+checks+" fallback assertions\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("stream","\nFAIL "+android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void policy() {
        SourceFailover f=new SourceFailover();
        for(int seconds:new int[]{5,10,15,30}) {
            f.configure(seconds); f.start(3,2,100,false);
            verify("deadline before "+seconds,f.next(100+seconds*1000-1)==SourceFailover.WAIT);
            verify("wrap at deadline "+seconds,f.next(100+seconds*1000)==0);
        }
        f.configure(5); f.start(3,2,0,false); f.start(3,0,5000,true);
        verify("retry preserves attempted sources",f.next(10000)==1);
        f.start(3,1,10000,true); verify("finite round",f.next(15000)==SourceFailover.EXHAUSTED);
        verify("exhaustion reported once",f.next(20000)==SourceFailover.WAIT);
        f.progress(20000); f.progress(21000); f.progress(22000); verify("sustained recovery starts new outage budget",f.next(27000)==2);
        f.start(1,0,0,false); verify("single source",f.next(5000)==SourceFailover.EXHAUSTED);
        f.configure(0); f.start(3,0,0,false); verify("disabled still ends waiting",f.next(18000)==SourceFailover.MANUAL_TIMEOUT);
        f.configure(5); f.start(3,0,0,false); f.pause(3000); f.resume(9000); verify("pause preserves remaining budget",f.next(10999)==SourceFailover.WAIT); verify("resume does not grant fresh interval",f.next(11000)==1);
        f.configure(30); f.start(4,0,0,false); f.start(4,1,30000,true);
        verify("round stops at sixty seconds",f.next(60000)==SourceFailover.ROUND_LIMIT);
        verify("round limit leaves unattempted sources",f.attemptedCount()==2);
        f.start(4,2,60000,false); verify("manual continuation starts fresh budget",f.next(89999)==SourceFailover.WAIT);
        f.configure(5);
        f.start(2,1,14000,false); verify("manual source starts fresh round",f.next(19000)==0);
    }
    private void verify(String label,boolean ok){if(!ok)throw new AssertionError(label);checks++;}
    private Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new RuntimeException(e);}}
    private void invoke(String name,Class[] types,Object...args){try{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
    private void callUi(Runnable r){Throwable[] error=new Throwable[1];runOnMainSync(()->{try{r.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private int source(){int[] value=new int[1];callUi(()->value[0]=(Integer)field(activity,"sourceIndex"));return value[0];}
    private void awaitSource(int expected,long max){long end=SystemClock.uptimeMillis()+max;while(source()!=expected&&SystemClock.uptimeMillis()<end)SystemClock.sleep(100);}
}
