package com.vasthread.webviewtv.navigationtest;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.vasthread.webviewtv.reconstructed.*;
import org.json.*;
import java.lang.reflect.*;
import java.io.IOException;

/** Release fixtures and real settings/browser handoff, without downloading APKs. */
public final class UpdateTest extends Instrumentation {
    private int checks;
    private MainActivity activity;
    private boolean live;
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);live="1".equals(args.getString("live"));start();}
    @Override public void onStart(){Bundle output=new Bundle();try{
        policy();
        ConfigRepository repo=new ConfigRepository(getTargetContext());repo.load();
        repo.save(new JSONObject("{\"version\":1,\"channelSourceUrl\":\"\",\"epg\":{\"enabled\":false},\"channels\":[{\"name\":\"Update test\",\"group\":\"Test\",\"urls\":[\"http://127.0.0.1:9/test\"]}]}"));
        getTargetContext().getSharedPreferences("tv",0).edit().clear().putBoolean("githubChannelSourceMigratedV1",true).commit();
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        UpdateChecker.Release release=parse(release("v0.4.19"),"0.4.18","armeabi-v7a");
        ui(()->{
            invoke("openSettings");
            SettingsView settings=(SettingsView)field(activity,"settingsView");
            TextView checkButton=(TextView)field(settings,"checkUpdateButton");
            TextView download=(TextView)field(settings,"downloadUpdateButton");
            TextView status=(TextView)field(settings,"updateStatusLabel");
            check(settings.isOpen() && checkButton.isFocusable(),"check action accessible to remote");
            check(download.getVisibility()==View.GONE,"download hidden before successful check");
            EditText draft=(EditText)field(settings,"channelUrl");
            draft.setText("https://example.org/draft.json");
            settings.updateVersionStatus("正在检查最新版本…",true,false,false);
            check(!checkButton.isEnabled(),"prevent duplicate requests while checking");
            settings.updateVersionStatus("连接超时，请检查网络后重试",false,false,false);
            check(checkButton.isEnabled() && download.getVisibility()==View.GONE,"failure supports retry without stale download");
            settings.updateVersionStatus("当前已是最新版本",false,false,false);
            check(download.getVisibility()==View.GONE,"same version hides download");
            settings.updateVersionStatus("发现新版本 0.4.19",false,true,true);
            set(activity,"availableUpdate",release);
            check(download.getVisibility()==View.VISIBLE && download.isFocusable(),"new release download focusable");
            check(status.getText().toString().contains("0.4.19"),"new version displayed");
            check(draft.getText().toString().equals("https://example.org/draft.json"),"check preserves unsaved settings");
            check(!repo.snapshot().optString("channelSourceUrl").contains("draft"),"check never commits draft");
        });
        // Intercept external navigation so the regression never actually downloads an APK.
        final Intent[] launched=new Intent[1];
        ActivityMonitor monitor=new ActivityMonitor(){
            @Override public ActivityResult onStartActivity(Intent intent){
                if(Intent.ACTION_VIEW.equals(intent.getAction())){launched[0]=intent;return new ActivityResult(Activity.RESULT_CANCELED,null);}
                return null;
            }
        };
        addMonitor(monitor);
        ui(()->((TextView)field(field(activity,"settingsView"),"downloadUpdateButton")).performClick());
        removeMonitor(monitor);
        check(launched[0]!=null && launched[0].hasCategory(Intent.CATEGORY_BROWSABLE),"download opens browsable system intent");
        check(release.downloadUrl.equals(launched[0].getDataString()),"browser receives chosen ARMv7 APK URL");
        ui(()->{
            SettingsView settings=(SettingsView)field(activity,"settingsView");
            check(settings.isOpen(),"browser handoff leaves settings open for return");
            check(((EditText)field(settings,"channelUrl")).getText().toString().contains("draft.json"),"browser handoff preserves draft");
            settings.updateVersionStatus("暂无匹配安装包",false,true,false);
            check(((TextView)field(settings,"downloadUpdateButton")).getText().toString().contains("发布页"),"missing asset clearly offers release page");
            invoke("closeSettings");
            settings.updateVersionStatus("检查失败",false,false,false);
            check(!settings.isOpen(),"late check result does not reopen closed settings");
        });
        if(live){
            ui(()->{invoke("openSettings");invoke("checkVersionUpdate");check((Boolean)field(activity,"updateChecking"),"check starts asynchronously");});
            long deadline=android.os.SystemClock.uptimeMillis()+35000;
            final boolean[] checking={true};
            while(checking[0] && android.os.SystemClock.uptimeMillis()<deadline){
                android.os.SystemClock.sleep(100);
                ui(()->checking[0]=(Boolean)field(activity,"updateChecking"));
            }
            ui(()->{
                check(!checking[0],"live request finishes");
                check(field(activity,"availableUpdate")!=null,"live check finds published release: "+((TextView)field(field(activity,"settingsView"),"updateStatusLabel")).getText());
                ((ScrollView)field(field(activity,"settingsView"),"scroll")).fullScroll(View.FOCUS_DOWN);
            });
            android.os.SystemClock.sleep(350);
            android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream image=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"update-settings.png"))){
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);
            }finally{screenshot.recycle();}
        }
        output.putString("stream","\nPASS "+checks+" update assertions\n");finish(Activity.RESULT_OK,output);
    }catch(Throwable error){output.putString("stream","\nFAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,output);}}

    private JSONObject release(String tag)throws Exception{
        JSONObject release=new JSONObject().put("tag_name",tag).put("draft",false).put("prerelease",false);
        JSONArray assets=new JSONArray();
        for(String arch:new String[]{"universal","armeabi-v7a"}){
            String name="WebViewTV-"+tag.replaceFirst("^v", "")+"-android9plus-"+arch+"-release.apk";
            assets.put(new JSONObject().put("name",name).put("state","uploaded").put("size",100)
                .put("browser_download_url",UpdateChecker.REPOSITORY+"/releases/download/"+tag+"/"+name));
        }
        return release.put("assets",assets);
    }
    private UpdateChecker.Release parse(JSONObject json,String installed,String...abis)throws Exception{
        return UpdateChecker.parse(json.toString(),installed,abis);
    }
    private void reject(JSONObject json,String installed,String label)throws Exception{
        boolean rejected=false;try{parse(json,installed,"arm64-v8a");}catch(IOException expected){rejected=true;}check(rejected,label);
    }
    private void policy()throws Exception{
        JSONObject r=release("v0.4.19");
        check(parse(r,"0.4.18","arm64-v8a","armeabi-v7a").downloadUrl.contains("universal"),"64 bit prefers universal");
        check(parse(r,"0.4.18","armeabi-v7a").downloadUrl.contains("armeabi-v7a"),"32 bit prefers ARMv7");
        check(parse(r,"0.4.18","x86_64").downloadUrl.contains("universal"),"emulator chooses universal");
        check(parse(r,"0.4.18").downloadUrl.contains("universal"),"unknown ABI chooses universal");
        check(parse(r,"0.4.9").newer,"numeric patch comparison");
        check(!parse(r,"0.4.19").newer,"equal version");
        check(!parse(r,"0.5.0").newer,"never downgrade minor");
        check(parse(release("v0.10.0"),"0.9.99").newer,"numeric minor comparison");
        check(parse(release("v1.0.0"),"0.99.99").newer,"major comparison");
        check(!parse(r,"1.0.0").newer,"never downgrade major");
        check(parse(release("0.4.19"),"v0.4.18").newer,"optional v prefix");
        reject(release("v0.4.19-beta"),"0.4.18","reject prerelease tag");
        reject(release("v0.4.19").put("draft",true),"0.4.18","reject draft");
        reject(release("v0.4.19").put("prerelease",true),"0.4.18","reject prerelease flag");
        reject(release("../../evil"),"0.4.18","reject malformed tag");
        reject(r,"unknown","unknown installed version cannot claim latest");
        reject(new JSONObject(),"0.4.18","malformed response is failure");
        r=release("v0.4.19");r.getJSONArray("assets").remove(1);
        check(parse(r,"0.4.18","armeabi-v7a").downloadUrl.contains("universal"),"ARMv7 falls back to universal");
        r.put("assets",new JSONArray());
        check(!parse(r,"0.4.18").directApk && parse(r,"0.4.18").downloadUrl.endsWith("/tag/v0.4.19"),"missing APK uses constructed release page");
        for(String url:new String[]{"https://evil.test/update.apk","http://github.com/qq994865664/WebViewTV/releases/download/v0.4.19/fake.apk","javascript:alert(1)"}){
            r=release("v0.4.19");r.getJSONArray("assets").getJSONObject(0).put("browser_download_url",url);
            check(!parse(r,"0.4.18","arm64-v8a").directApk,"reject unsafe asset URL");
        }
        r=release("v0.4.19");r.getJSONArray("assets").getJSONObject(0).put("state","new");
        check(!parse(r,"0.4.18").directApk,"ignore incomplete asset");
        r=release("v0.4.19");r.getJSONArray("assets").getJSONObject(0).put("size",0);
        check(!parse(r,"0.4.18").directApk,"ignore empty asset");
    }
    private Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new RuntimeException(e);}}
    private void set(Object owner,String name,Object value){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}catch(Exception e){throw new RuntimeException(e);}}
    private void invoke(String name){try{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void ui(Runnable r){Throwable[] error=new Throwable[1];runOnMainSync(()->{try{r.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
}
