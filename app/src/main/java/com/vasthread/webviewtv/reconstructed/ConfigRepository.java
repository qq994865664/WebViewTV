package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Editable local settings plus a separately timed remote channel-list cache. */
public final class ConfigRepository {
    public static final String DEFAULT_CHANNEL_URL = "https://raw.githubusercontent.com/qq994865664/WebViewTV/main/config/tv-config.json";
    private static final String GITHUB_CONTENTS_URL = "https://api.github.com/repos/qq994865664/WebViewTV/contents/config/tv-config.json?ref=main";
    public static final String DEFAULT_EPG_URL = "https://free-epg.de/api/epg/cn.xml.gz";
    public static final int[] UPDATE_HOURS = {2, 6, 24, 72, 120, 240, 720};
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHANNELS = 5000;
    private final Context context;
    private final File directory, configFile, backupFile, cacheFile, metadataFile;
    private JSONObject current, metadata = new JSONObject();
    private String lastError = "", lastWarning = "";
    private long channelGeneration;
    private boolean refreshing;

    public static final class RefreshResult {
        public final boolean changed, skipped;
        public final String error, warning;
        public final long timestamp;
        public final int channelCount;
        RefreshResult(boolean changed, boolean skipped, String error, String warning, long timestamp, int count) {
            this.changed = changed; this.skipped = skipped; this.error = error;
            this.warning = warning; this.timestamp = timestamp; this.channelCount = count;
        }
        public boolean success() { return error == null; }
    }

    private static final class Validated {
        final JSONObject config;
        final String warning;
        Validated(JSONObject config, String warning) { this.config = config; this.warning = warning; }
    }
    private static final class ChannelData {
        final JSONArray channels;
        final String warning;
        ChannelData(JSONArray channels, String warning) { this.channels = channels; this.warning = warning; }
    }
    private static final class Download {
        String body, etag = "", lastModified = "", downloadUrl = "";
        boolean notModified;
    }

    public ConfigRepository(Context context) {
        this.context = context.getApplicationContext();
        File parent = this.context.getExternalFilesDir(null);
        if (parent == null) parent = this.context.getFilesDir();
        directory = new File(parent, "config");
        configFile = new File(directory, "tv-config.json");
        backupFile = new File(directory, "tv-config.last-good.json");
        cacheFile = new File(directory, "channel-cache.json");
        metadataFile = new File(directory, "channel-cache.meta.json");
    }

    /** Reads the editable file first. A bad file is preserved and reported while last-good is used. */
    public synchronized JSONObject load() throws IOException {
        ensureDirectory();
        lastError = ""; lastWarning = "";
        Validated loaded = null;
        boolean badPrimary = false;
        if (configFile.isFile()) {
            try { loaded = validateConfig(parse(read(configFile)), null); }
            catch (IOException e) { badPrimary = true; lastError = "配置文件无效：" + e.getMessage(); }
        }
        if (loaded == null && backupFile.isFile()) {
            try {
                loaded = validateConfig(parse(read(backupFile)), null);
                if (badPrimary) lastError += "；已使用上次有效配置，原文件保留供修正";
            } catch (IOException e) {
                lastError += (lastError.isEmpty() ? "" : "；") + "备份无效：" + e.getMessage();
            }
        }
        if (loaded == null) {
            loaded = validateConfig(builtInChannels(), null);
            if (badPrimary) lastError += "；已临时使用内置频道，原文件保留供修正";
            else if (!configFile.exists()) {
                atomicWrite(configFile, encode(loaded.config));
                atomicWrite(backupFile, encode(loaded.config));
            }
        }
        current = loaded.config; lastWarning = loaded.warning; channelGeneration++;
        metadata = new JSONObject();
        if (metadataFile.isFile()) {
            try {
                Object value = parse(read(metadataFile));
                if (value instanceof JSONObject) metadata = (JSONObject) value;
            } catch (IOException ignored) { /* A damaged timestamp simply makes the next update due. */ }
        }
        if (metadata.optString("url", "").equals(current.optString("channelSourceUrl", "")) && cachedTextFor(current.optString("channelSourceUrl", "")) == null) metadata = new JSONObject();
        return cloneObject(current);
    }

    /** Repairs a known typo in the shipped snapshot; user-imported files remain strictly validated. */
    public JSONArray builtInChannels() throws IOException {
        try (InputStream input = context.getAssets().open("china_mainland.json")) {
            Object parsed = parse(read(input));
            if (!(parsed instanceof JSONArray)) throw new IOException("内置频道格式错误");
            JSONArray result = (JSONArray)parsed;
            for (int i = 0; i < result.length(); i++) {
                JSONObject channel = result.optJSONObject(i);
                if (channel == null || !"海峡卫视".equals(channel.optString("name"))) continue;
                JSONArray sources = channel.optJSONArray("urls");
                if (sources == null) continue;
                for (int j = 0; j < sources.length(); j++) {
                    JSONObject source = sources.optJSONObject(j);
                    if (source == null) continue;
                    String address = source.optString("url").trim();
                    if (address.startsWith("海峡卫视https://www.fjtv.net/")) put(source, "url", address.substring(4));
                }
            }
            return result;
        }
    }
    public synchronized JSONArray channels() { requireLoaded(); return cloneArray(current.optJSONArray("channels")); }
    public synchronized JSONObject snapshot() { requireLoaded(); return cloneObject(current); }
    public File file() { return configFile; }
    public synchronized String lastError() { return lastError; }
    public synchronized String lastWarning() { return lastWarning; }
    public synchronized long lastRefreshAt() {
        requireLoaded();
        return metadata.optString("url", "").equals(current.optString("channelSourceUrl", ""))
                ? metadata.optLong("fetchedAt", 0) : 0;
    }

    /** Invalid edits never replace the current file or its last-good backup. */
    public synchronized void save(JSONObject config) throws IOException {
        requireLoaded();
        Validated validated = validateConfig(cloneObject(config), null);
        persist(validated.config);
        lastWarning = validated.warning; lastError = "";
    }

    /** A bare array changes only channels; a full object imports all settings. */
    public synchronized void importText(String text) throws IOException {
        requireLoaded();
        Object value = parse(text);
        Validated validated = validateConfig(value, value instanceof JSONArray ? current : null);
        persist(validated.config);
        lastWarning = validated.warning; lastError = "";
    }

    public synchronized String exportText() { requireLoaded(); return encode(current); }

    /** The interval is the remote JSON list TTL. Local files and video buffers are not refreshed. */
    public synchronized boolean refreshDue() {
        requireLoaded();
        String url = current.optString("channelSourceUrl", "");
        if (url.isEmpty()) return false;
        long timestamp = lastRefreshAt();
        long now = System.currentTimeMillis();
        return timestamp <= 0 || now < timestamp || now - timestamp >= current.optInt("channelUpdateHours", 24) * 3600000L;
    }

    /** Call from a worker. The network request does not hold this repository's monitor. */
    public RefreshResult refresh(boolean force) {
        final String url, etag, modified, cacheText, preferredEndpoint;
        final long generation;
        synchronized (this) {
            requireLoaded();
            url = current.optString("channelSourceUrl", "");
            if (url.isEmpty() || (!force && !refreshDue()) || refreshing) return result(false, true, null, "");
            refreshing = true;
            generation = channelGeneration;
            cacheText = cachedTextFor(url);
            boolean cacheValid = cacheText != null;
            etag = cacheValid ? metadata.optString("etag", "") : "";
            modified = cacheValid ? metadata.optString("lastModified", "") : "";
            preferredEndpoint = metadata.optString("url", "").equals(url) ? metadata.optString("downloadUrl", url) : url;
        }
        try {
            Download download = download(url, etag, modified, preferredEndpoint);
            ChannelData data = null;
            if (download.notModified) {
                if (cacheText == null) throw new IOException("服务器返回 304，但没有有效的频道缓存");
                data = validateChannels((JSONArray) parse(cacheText));
            } else {
                Object value = parse(download.body);
                JSONArray raw = value instanceof JSONArray ? (JSONArray) value :
                        value instanceof JSONObject ? ((JSONObject) value).optJSONArray("channels") : null;
                if (raw == null) throw new IOException("远程文件应是频道数组，或包含 channels 数组的 JSON 对象");
                data = validateChannels(raw);
            }
            synchronized (this) {
                if (generation != channelGeneration || !url.equals(current.optString("channelSourceUrl", "")))
                    return result(false, true, null, "配置在下载期间已改变，已放弃旧结果");
                String warning = data == null ? "" : data.warning;
                boolean changed = data != null && !data.channels.toString().equals(current.optJSONArray("channels").toString());
                if (changed) {
                    JSONObject replacement = cloneObject(current);
                    put(replacement, "channels", data.channels);
                    persist(replacement);
                }
                long now = System.currentTimeMillis();
                JSONArray cached = data == null ? current.optJSONArray("channels") : data.channels;
                String nextCacheText = cached.toString();
                JSONObject nextMetadata = new JSONObject();
                put(nextMetadata, "url", url); put(nextMetadata, "fetchedAt", now);
                put(nextMetadata, "downloadUrl", download.downloadUrl);
                put(nextMetadata, "etag", download.notModified && download.etag.isEmpty() ? etag : download.etag);
                put(nextMetadata, "lastModified", download.notModified && download.lastModified.isEmpty() ? modified : download.lastModified);
                put(nextMetadata, "sha256", digest(nextCacheText.getBytes(StandardCharsets.UTF_8)));
                put(nextMetadata, "warning", warning);
                try {
                    atomicWrite(cacheFile, nextCacheText);
                    atomicWrite(metadataFile, encode(nextMetadata));
                } catch (IOException e) {
                    lastError = (changed ? "频道已更新，但缓存记录保存失败：" : "缓存记录保存失败：") + e.getMessage();
                    return result(changed, false, lastError, warning);
                }
                metadata = nextMetadata; lastError = ""; lastWarning = warning;
                return new RefreshResult(changed, false, null, warning, now, cached.length());
            }
        } catch (Exception e) {
            synchronized (this) {
                if (generation != channelGeneration || !url.equals(current.optString("channelSourceUrl", "")))
                    return result(false, true, null, "配置在下载期间已改变，已放弃旧请求");
                lastError = "频道更新失败，继续使用当前频道：" + readable(e);
                return result(false, false, lastError, "");
            }
        } finally {
            synchronized (this) { refreshing = false; }
        }
    }

    private RefreshResult result(boolean changed, boolean skipped, String error, String warning) {
        return new RefreshResult(changed, skipped, error, warning, lastRefreshAt(), current.optJSONArray("channels").length());
    }
    private void requireLoaded() { if (current == null) throw new IllegalStateException("请先调用 ConfigRepository.load()"); }
    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建配置目录：" + directory);
    }
    private void persist(JSONObject next) throws IOException {
        ensureDirectory();
        atomicWrite(backupFile, encode(current));
        atomicWrite(configFile, encode(next));
        boolean changed = !next.optString("channelSourceUrl", "").equals(current.optString("channelSourceUrl", "")) ||
                !next.optJSONArray("channels").toString().equals(current.optJSONArray("channels").toString());
        current = next;
        if (changed) channelGeneration++;
    }
    private String cachedTextFor(String url) {
        if (!url.equals(metadata.optString("url", "")) || !cacheFile.isFile()) return null;
        try {
            String text = read(cacheFile);
            return digest(text.getBytes(StandardCharsets.UTF_8)).equals(metadata.optString("sha256", "")) ? text : null;
        } catch (IOException e) { return null; }
    }

    private static Validated validateConfig(Object input, JSONObject base) throws IOException {
        JSONObject config;
        if (input instanceof JSONArray) {
            config = base == null ? defaults() : cloneObject(base);
            put(config, "channels", input);
        } else if (input instanceof JSONObject) config = cloneObject((JSONObject) input);
        else throw new IOException("配置必须是 JSON 对象或频道数组");
        if (integer(config, "version", 1) != 1) throw new IOException("仅支持配置 version: 1");
        put(config, "version", 1);
        String source = string(config, "channelSourceUrl", DEFAULT_CHANNEL_URL, false, 4096);
        if (!source.isEmpty()) checkUrl(source);
        put(config, "channelSourceUrl", source);
        int hours = integer(config, "channelUpdateHours", 24);
        boolean allowed = false;
        for (int interval : UPDATE_HOURS) if (interval == hours) allowed = true;
        if (!allowed) throw new IOException("channelUpdateHours 必须是 2、6、24、72、120、240 或 720");
        put(config, "channelUpdateHours", hours);
        JSONObject epg = section(config, "epg");
        put(epg, "enabled", bool(epg, "enabled", true));
        String epgUrl = string(epg, "url", DEFAULT_EPG_URL, false, 4096);
        if (!epgUrl.isEmpty()) checkUrl(epgUrl);
        put(epg, "url", epgUrl);
        int epgHours = integer(epg, "refreshHours", 6);
        if (epgHours < 1 || epgHours > 720) throw new IOException("epg.refreshHours 必须在 1 到 720 小时之间");
        put(epg, "refreshHours", epgHours); put(config, "epg", epg);
        JSONObject playback = section(config, "playback");
        put(playback, "fastSwitch", bool(playback, "fastSwitch", true));
        put(playback, "rememberSource", bool(playback, "rememberSource", true));
        int sourceTimeout = integer(playback, "sourceTimeoutSeconds", 15);
        if (!SourceFailover.validTimeout(sourceTimeout)) throw new IOException("playback.sourceTimeoutSeconds 必须是 0、5、10、15 或 30；0 表示关闭");
        put(playback, "sourceTimeoutSeconds", sourceTimeout);
        put(config, "playback", playback);
        JSONArray raw = config.optJSONArray("channels");
        if (raw == null) throw new IOException("缺少 channels 频道数组");
        ChannelData data = validateChannels(raw);
        put(config, "channels", data.channels);
        return new Validated(config, data.warning);
    }
    private static JSONObject defaults() {
        JSONObject object = new JSONObject();
        put(object, "version", 1); put(object, "channelSourceUrl", DEFAULT_CHANNEL_URL); put(object, "channelUpdateHours", 24);
        JSONObject epg = new JSONObject(); put(epg, "enabled", true); put(epg, "url", DEFAULT_EPG_URL); put(epg, "refreshHours", 6);
        JSONObject playback = new JSONObject(); put(playback, "fastSwitch", true); put(playback, "rememberSource", true);
        put(playback, "sourceTimeoutSeconds", 15);
        put(object, "epg", epg); put(object, "playback", playback);
        return object;
    }
    private static ChannelData validateChannels(JSONArray raw) throws IOException {
        if (raw.length() == 0) throw new IOException("channels 不能为空");
        if (raw.length() > MAX_CHANNELS) throw new IOException("频道数量超过 " + MAX_CHANNELS);
        JSONArray valid = new JSONArray();
        ArrayList<String> issues = new ArrayList<>();
        int skippedChannels = 0, skippedSources = 0;
        for (int i = 0; i < raw.length(); i++) {
            try {
                JSONObject record = raw.optJSONObject(i);
                if (record == null) throw new IOException("应为对象");
                record = cloneObject(record);
                put(record, "name", string(record, "name", "", true, 120));
                put(record, "group", string(record, "group", "", true, 80));
                if (record.has("id")) put(record, "id", string(record, "id", "", false, 160));
                if (record.has("epgId")) put(record, "epgId", string(record, "epgId", "", false, 160));
                JSONArray urls = record.optJSONArray("urls");
                if (urls == null || urls.length() == 0) throw new IOException("urls 不能为空");
                if (urls.length() > 32) throw new IOException("urls 最多支持 32 条来源");
                JSONArray sources = new JSONArray();
                for (int j = 0; j < urls.length(); j++) {
                    try {
                        Object rawSource = urls.opt(j);
                        JSONObject source = rawSource instanceof JSONObject ? cloneObject((JSONObject) rawSource) : new JSONObject();
                        if (rawSource instanceof String) put(source, "url", rawSource);
                        String url = string(source, "url", "", true, 4096);
                        checkUrl(url); put(source, "url", url);
                        if (source.has("extra")) string(source, "extra", "", false, 16384); // Validate without changing player-specific content.
                        sources.put(source);
                    } catch (IOException e) {
                        skippedSources++; if (issues.size() < 4) issues.add("频道 " + (i + 1) + " 来源 " + (j + 1) + "：" + e.getMessage());
                    }
                }
                if (sources.length() == 0) throw new IOException("没有有效的 http(s) 来源");
                put(record, "urls", sources); valid.put(record);
            } catch (IOException e) {
                skippedChannels++; if (issues.size() < 4) issues.add("频道 " + (i + 1) + "：" + e.getMessage());
            }
        }
        if (valid.length() == 0) throw new IOException("没有有效频道；" + join(issues));
        String warning = skippedChannels == 0 && skippedSources == 0 ? "" :
                "忽略 " + skippedChannels + " 个无效频道、" + skippedSources + " 个无效来源；" + join(issues);
        return new ChannelData(valid, warning);
    }
    private static String join(List<String> messages) {
        StringBuilder builder = new StringBuilder();
        for (String message : messages) { if (builder.length() > 0) builder.append("；"); builder.append(message); }
        return builder.toString();
    }
    private static JSONObject section(JSONObject object, String key) throws IOException {
        if (!object.has(key)) return new JSONObject();
        JSONObject section = object.optJSONObject(key);
        if (section == null) throw new IOException(key + " 必须为对象");
        return section;
    }
    private static String string(JSONObject object, String key, String fallback, boolean required, int maximum) throws IOException {
        if (!object.has(key)) {
            if (required) throw new IOException("缺少 " + key);
            return fallback;
        }
        Object raw = object.opt(key);
        if (!(raw instanceof String)) throw new IOException(key + " 必须为字符串");
        String value = ((String) raw).trim();
        if (required && value.isEmpty()) throw new IOException(key + " 不能为空");
        if (value.length() > maximum) throw new IOException(key + " 长度超过 " + maximum);
        return value;
    }
    private static int integer(JSONObject object, String key, int fallback) throws IOException {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue())
            throw new IOException(key + " 必须为整数");
        return ((Number) value).intValue();
    }
    private static boolean bool(JSONObject object, String key, boolean fallback) throws IOException {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw new IOException(key + " 必须为 true 或 false");
        return (Boolean) value;
    }
    private static void checkUrl(String value) throws IOException {
        try {
            URL url = new URL(value);
            if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https")) || url.getHost().isEmpty())
                throw new IOException("地址必须为完整的 http(s) URL");
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf(' ') >= 0)
                throw new IOException("URL 不能包含空白字符");
        } catch (java.net.MalformedURLException e) { throw new IOException("地址必须为完整的 http(s) URL", e); }
    }
    private static Object parse(String text) throws IOException {
        if (text == null) throw new IOException("配置为空");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("文件不能超过 8 MiB");
        text = text.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        try {
            JSONTokener tokens = new JSONTokener(text);
            Object value = tokens.nextValue();
            if (tokens.nextClean() != 0) throw new IOException("JSON 末尾有多余内容");
            return value;
        } catch (JSONException e) { throw new IOException("JSON 格式错误：" + e.getMessage(), e); }
    }
    private static String read(File file) throws IOException { try (InputStream input = new FileInputStream(file)) { return read(input); } }
    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[8192]; int count;
        while ((count = input.read(block)) != -1) {
            if (bytes.size() + count > MAX_BYTES) throw new IOException("文件不能超过 8 MiB");
            bytes.write(block, 0, count);
        }
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IOException("文件必须使用 UTF-8 编码", e); }
    }
    private static void atomicWrite(File file, String text) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(text.getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static String digest(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(Locale.US, "%02x", b & 255));
            return value.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** Only the bundled public repository has an alternate official GitHub endpoint. */
    private static Download download(String original, String etag, String modified, String preferredEndpoint) throws IOException {
        if (!DEFAULT_CHANNEL_URL.equals(original)) return downloadAddress(original, etag, modified);
        String first = GITHUB_CONTENTS_URL.equals(preferredEndpoint) ? GITHUB_CONTENTS_URL : original;
        String second = GITHUB_CONTENTS_URL.equals(first) ? original : GITHUB_CONTENTS_URL;
        boolean sameEndpoint = first.equals(preferredEndpoint);
        try {
            return downloadAddress(first, sameEndpoint ? etag : "", sameEndpoint ? modified : "");
        } catch (IOException firstError) {
            try {
                // Validators belong to the successful endpoint's representation, never another host.
                Download fallback = downloadAddress(second, "", "");
                android.util.Log.i("WVTResearch", "Channel config retrieved through alternate official GitHub endpoint");
                return fallback;
            } catch (IOException secondError) {
                secondError.addSuppressed(firstError);
                throw secondError;
            }
        }
    }

    private static Download downloadAddress(String original, String etag, String modified) throws IOException {
        String address = original;
        for (int redirects = 0; redirects <= 4; redirects++) {
            checkUrl(address);
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "WebViewTVResearch/0.3.1 (channel-config)");
            connection.setRequestProperty("Accept", GITHUB_CONTENTS_URL.equals(address)
                    ? "application/vnd.github.raw+json" : "application/json, text/plain;q=0.9, */*;q=0.5");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
            if (!modified.isEmpty()) connection.setRequestProperty("If-Modified-Since", modified);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("重定向响应缺少 Location");
                    address = new URL(new URL(address), location).toString();
                    continue;
                }
                Download result = new Download();
                result.downloadUrl = original;
                String returnedEtag = connection.getHeaderField("ETag");
                String returnedModified = connection.getHeaderField("Last-Modified");
                result.etag = returnedEtag == null ? "" : returnedEtag;
                result.lastModified = returnedModified == null ? "" : returnedModified;
                if (status == 304) { result.notModified = true; return result; }
                if (status != 200) throw new IOException("HTTP " + status);
                if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("远程文件不能超过 8 MiB");
                InputStream raw = connection.getInputStream();
                if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) raw = new GZIPInputStream(raw);
                try (InputStream input = raw) { result.body = read(input); }
                return result;
            } finally { connection.disconnect(); }
        }
        throw new IOException("重定向次数过多");
    }
    private static String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static JSONObject cloneObject(JSONObject object) {
        try { return new JSONObject(object.toString()); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static JSONArray cloneArray(JSONArray array) {
        try { return new JSONArray(array.toString()); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static String encode(JSONObject object) {
        try { return object.toString(2) + "\n"; } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
