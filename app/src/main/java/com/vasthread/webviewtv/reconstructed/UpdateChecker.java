package com.vasthread.webviewtv.reconstructed;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Public release metadata only; APK downloads are delegated to the browser. */
public final class UpdateChecker {
    public static final String REPOSITORY = "https://github.com/qq994865664/WebViewTV";
    private static final String ENDPOINT = "https://api.github.com/repos/qq994865664/WebViewTV/releases/latest";
    public static final class Release {
        public final String version, downloadUrl;
        public final boolean newer, directApk;
        Release(String version, String url, boolean newer, boolean direct) {
            this.version=version; downloadUrl=url; this.newer=newer; directApk=direct;
        }
    }
    public static Release fetch(String installed, String[] abis) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(ENDPOINT).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setRequestProperty("Accept","application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        connection.setRequestProperty("User-Agent","WebViewTV-UpdateCheck");
        try {
            int status=connection.getResponseCode();
            if(status==404)throw new IOException("暂未找到正式发布版本");
            if(status==403 || status==429)throw new IOException("检查过于频繁，请稍后重试");
            if(status!=200)throw new IOException("更新服务暂不可用，请稍后重试");
            try(InputStream input=connection.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted())throw new IOException("检查已取消");
                    if(bytes.size()+count>1024*1024)throw new IOException("更新信息过大，请稍后重试");
                    bytes.write(buffer,0,count);
                }
                return parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8),installed,abis);
            }
        } catch(java.net.SocketTimeoutException timeout) {
            throw new IOException("连接超时，请检查网络后重试");
        } finally { connection.disconnect(); }
    }
    private static int[] version(String value) throws IOException {
        if(value==null || !value.matches("v?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))
            throw new IOException("无法识别版本信息，请稍后重试");
        String[] parts=value.replaceFirst("^v", "").split("\\.");
        return new int[]{Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])};
    }
    public static Release parse(String json, String installed, String[] abis) throws IOException {
        try {
            JSONObject release=new JSONObject(json);
            if(release.getBoolean("draft") || release.getBoolean("prerelease"))
                throw new IOException("暂未找到正式发布版本");
            String tag=release.getString("tag_name");
            int[] latest=version(tag), current=version(installed);
            boolean newer=false;
            for(int i=0;i<3;i++) { if(latest[i]!=current[i]) { newer=latest[i]>current[i]; break; } }
            String display=tag.replaceFirst("^v", "");
            boolean arm32=false, arm64=false;
            if(abis!=null)for(String abi:abis) { arm32|="armeabi-v7a".equals(abi); arm64|="arm64-v8a".equals(abi); }
            String prefix="WebViewTV-"+display+"-android9plus-";
            String[] names=arm32&&!arm64 ? new String[]{prefix+"armeabi-v7a-release.apk",prefix+"universal-release.apk"}
                    :new String[]{prefix+"universal-release.apk"};
            JSONArray assets=release.optJSONArray("assets");
            for(String name:names)for(int i=0;assets!=null && i<assets.length();i++) {
                JSONObject asset=assets.optJSONObject(i);
                String expected=REPOSITORY+"/releases/download/"+tag+"/"+name;
                if(asset!=null && name.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))
                        && asset.optLong("size")>0 && expected.equals(asset.optString("browser_download_url")))
                    return new Release(display,expected,newer,true);
            }
            // No suitable APK: allow reviewing this release, never select a debug or wrong-ABI asset.
            return new Release(display,REPOSITORY+"/releases/tag/"+tag,newer,false);
        } catch(org.json.JSONException malformed) {
            throw new IOException("更新信息无效，请稍后重试");
        }
    }
}
