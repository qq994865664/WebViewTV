package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import com.vasthread.webviewtv.reconstructed.*;
import org.json.*;
import java.lang.reflect.*;

/** Real Activity integration and wall-clock dismissal; isolated app data. */
public final class InformationTest extends Instrumentation {
    private MainActivity activity;
    private ChannelInfoView info;
    private TvMenuView menu;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ConfigRepository repo = new ConfigRepository(getTargetContext());
            repo.load();
            JSONObject config = new JSONObject("{\"version\":1,\"channelSourceUrl\":\"\",\"epg\":{\"enabled\":false},\"playback\":{\"sourceTimeoutSeconds\":0},\"channels\":[{\"name\":\"First\",\"group\":\"Test\",\"urls\":[\"http://127.0.0.1:9/a\",\"http://127.0.0.1:9/b\"]},{\"name\":\"Second\",\"group\":\"Test\",\"urls\":[\"http://127.0.0.1:9/c\"]}]}");
            repo.save(config);
            getTargetContext().getSharedPreferences("tv",0).edit().clear().putBoolean("githubChannelSourceMigratedV1",true).commit();
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            ui(() -> {
                info = (ChannelInfoView)field("channelInfo"); menu = (TvMenuView)field("menu");
                verify("information separate from menu", info.getParent() != menu && menu.getChildCount()==1);
                verify("banner does not take input", !info.isClickable() && !info.isFocusable());
                invoke("showMenu",new Class[]{boolean.class},true);
                verify("menu suppresses information", menu.isOpen() && !visible());
                invoke("play",new Class[]{int.class,int.class},1,-999);
                verify("channel selection hides menu before information", menu.getVisibility()==View.GONE && visible());
                verify("normal HUD suppressed", ((View)field("hud")).getVisibility()==View.GONE);
            });
            SystemClock.sleep(3300);
            ui(() -> {
                verify("still visible before five seconds", visible());
                invoke("play",new Class[]{int.class,int.class},0,-999);
            });
            SystemClock.sleep(2100);
            ui(() -> {
                verify("rapid channel change replaces old deadline", visible());
                info.setProgramme("Programme refreshed", "Next programme");
                invoke("play",new Class[]{int.class,int.class},0,1);
            });
            SystemClock.sleep(3100);
            ui(() -> {
                verify("five seconds hides without refresh extending deadline", !visible());
                info.setProgramme("Background refresh", "");
                verify("background refresh cannot reopen banner", !visible());
                invoke("showMenu",new Class[]{boolean.class},true);
                invoke("showMenu",new Class[]{boolean.class},false);
                verify("closing menu alone does not show information", !visible());
                invoke("play",new Class[]{int.class,int.class},1,-999);
                verify("new channel shows again", visible());
                invoke("showMenu",new Class[]{boolean.class},true);
                verify("menu immediately dismisses active banner", !visible());
            });
            SystemClock.sleep(5200);
            ui(() -> {
                verify("old callback cannot reopen information over menu", menu.isOpen() && !visible());
                invoke("play",new Class[]{int.class,int.class},0,-999);
                invoke("openSettings",new Class[]{});
                verify("settings hides information", !visible());
                invoke("closeSettings",new Class[]{});
                invoke("play",new Class[]{int.class,int.class},1,-999);
                invoke("openProgramme",new Class[]{});
                verify("programme panel hides information", !visible());
                ((EpgView)field("epgView")).close();
                invoke("play",new Class[]{int.class,int.class},0,-999);
                activity.onWindowFocusChanged(false);
                verify("losing focus hides information", !visible());
                activity.onWindowFocusChanged(true);
                verify("regaining focus does not replay information", !visible());
            });
            result.putString("stream","\nPASS "+checks+" information assertions\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream","\nFAIL "+android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private boolean visible() {
        PlaybackStatusView loading=(PlaybackStatusView)field("playbackStatus");
        return info.getVisibility()==View.VISIBLE || (loading.getVisibility()==View.VISIBLE && !(Boolean)field("playbackFailed") && SystemClock.elapsedRealtime() < (Long)field("infoUntil"));
    }
    private void verify(String message, boolean condition) { if (!condition) throw new AssertionError(message); checks++; }
    private Object field(String name) { try { Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); } catch(Exception e) { throw new RuntimeException(e); } }
    private void invoke(String name, Class[] types, Object... args) { try { Method m=MainActivity.class.getDeclaredMethod(name,types); m.setAccessible(true); m.invoke(activity,args); } catch(Exception e) { throw new RuntimeException(e); } }
    private void ui(Runnable action) {
        Throwable[] error=new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch(Throwable e) { error[0]=e; } });
        if(error[0]!=null) throw new AssertionError(error[0]);
    }
}
