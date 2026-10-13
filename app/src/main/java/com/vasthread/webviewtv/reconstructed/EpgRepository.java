package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import org.json.JSONArray;
import org.xmlpull.v1.XmlSerializer;
import java.security.MessageDigest;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** XMLTV data only. Call loadCached/refresh on a worker; lookup never performs I/O. */
public final class EpgRepository {
    private static final String MAGIC = "WVT_XMLTV_2";
    private static final long MAX_DOWNLOAD = 16L * 1024 * 1024;
    private static final long MAX_XML = 64L * 1024 * 1024;
    private static final int MAX_PROGRAMMES = 100000, MAX_CHANNELS = 20000;
    private static final Pattern DATE = Pattern.compile("^(\\d{8}(?:\\d{2}){0,3})(?:\\s*([+-]\\d{2}:?\\d{2}|Z|UTC|GMT))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CCTV = Pattern.compile("^(?:CCTV|央视|中央)(?:第)?([0-9]{1,2}|[一二三四五六七八九十]+)(.*)$");
    private final AtomicFile cache;
    private final File tempDirectory;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile String requestedSource = "", status = "未配置节目单";
    private volatile boolean stale, cacheExamined;
    private volatile Selection selection = Selection.all();
    private volatile JSONArray configuredChannels;

    public EpgRepository(Context context) {
        cache = new AtomicFile(new File(context.getFilesDir(), "epg-cache.dat"));
        tempDirectory = context.getCacheDir();
    }

    private static final class Selection {
        final Set<String> ids, names;
        final String fingerprint;
        final boolean all;
        Selection(Set<String> ids, Set<String> names, String fingerprint, boolean all) {
            this.ids = Collections.unmodifiableSet(ids); this.names = Collections.unmodifiableSet(names);
            this.fingerprint = fingerprint; this.all = all;
        }
        static Selection all() { return new Selection(new HashSet<>(), new HashSet<>(), "*", true); }
        boolean matches(List<String> names) {
            if (all) return true;
            for (String name : names) for (String alias : aliasesFor(name)) if (this.names.contains(alias)) return true;
            return false;
        }
    }

    /** No I/O. Only existing channels contribute IDs/names; repeated identical objects are free. */
    public void setChannels(JSONArray channels) {
        if (channels == configuredChannels) return;
        HashSet<String> ids = new HashSet<>(), names = new HashSet<>();
        TreeSet<String> keys = new TreeSet<>();
        if (channels != null) for (int i = 0; i < channels.length(); i++) {
            JSONObject item = channels.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("epgId", "").trim();
            if (!id.isEmpty()) { ids.add(id); keys.add("id:" + id); }
            else for (String alias : aliasesFor(item.optString("name", ""))) {
                names.add(alias); keys.add("name:" + alias);
            }
        }
        String fingerprint;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String key : keys) { digest.update(key.getBytes("UTF-8")); digest.update((byte)0); }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            fingerprint = hex.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        Selection next = new Selection(ids, names, fingerprint, false);
        if (!selection.fingerprint.equals(fingerprint)) {
            selection = next;
            cacheExamined = false;
            stale = false;
            status = "节目单匹配配置已更改";
        }
        configuredChannels = channels;
    }
    public static final class Program {
        public final long start, end;
        public final String title, description;
        Program(long start, long end, String title, String description) {
            this.start = start; this.end = end; this.title = title; this.description = description;
        }
    }

    public static final class NowNext {
        public final Program current, next;
        public final float progress;
        public final boolean stale;
        NowNext(Program current, Program next, long now, boolean stale) {
            this.current = current; this.next = next; this.stale = stale;
            progress = current == null ? 0 : Math.max(0, Math.min(1, (float)(now - current.start) / (current.end - current.start)));
        }
    }

    public static final class RefreshResult {
        public final boolean success, changed, stale;
        public final String message;
        public final long updatedAt;
        public final int channelCount, programmeCount;
        RefreshResult(boolean success, boolean changed, boolean stale, String message, Snapshot data) {
            this.success = success; this.changed = changed; this.stale = stale;
            this.message = message; updatedAt = data.updatedAt;
            channelCount = data.programs.size(); programmeCount = data.programmeCount;
        }
    }

    private static final class Snapshot {
        final String source;
        final long updatedAt;
        final Map<String, List<Program>> programs;
        final Map<String, String> aliases;
        final Set<String> ids;
        final int programmeCount;
        final Map<String, List<String>> channelNames;
        final String selectionFingerprint;
        Snapshot(String source, long updatedAt, Map<String, List<Program>> programs,
                 Map<String, String> aliases, Set<String> ids, int count) {
            this(source, updatedAt, programs, aliases, ids, count, new HashMap<>(), "*");
        }
        Snapshot(String source, long updatedAt, Map<String, List<Program>> programs,
                 Map<String, String> aliases, Set<String> ids, int count, Map<String, List<String>> names, String fingerprint) {
            this.source = source; this.updatedAt = updatedAt;
            this.programs = Collections.unmodifiableMap(programs);
            this.aliases = Collections.unmodifiableMap(aliases);
            this.ids = Collections.unmodifiableSet(ids); programmeCount = count;
            channelNames = Collections.unmodifiableMap(names); selectionFingerprint = fingerprint;
        }
        private static final Snapshot EMPTY = new Snapshot("", 0, new HashMap<>(), new HashMap<>(), new HashSet<>(), 0);
        static Snapshot empty() { return EMPTY; }
    }

    private static final class RawProgram {
        final long start;
        long end;
        final String title, description;
        RawProgram(long start, long end, String title, String description) {
            this.start = start; this.end = end; this.title = title; this.description = description;
        }
    }

    public long getLastUpdated() { return visibleSnapshot().updatedAt; }
    public String getStatus() { return status; }
    public boolean isStale() { return stale; }
    public int getProgrammeCount() { return visibleSnapshot().programmeCount; }
    public int getChannelCount() { return visibleSnapshot().programs.size(); }

    /** Also hides a previous source immediately; no network, disk, or lock is used. */
    public boolean refreshDue(String url, int hours) {
        String source = cleanUrl(url);
        if (!source.equals(requestedSource)) {
            requestedSource = source;
            stale = false;
            status = source.isEmpty() ? "未配置节目单" : "节目单尚未加载";
        }
        Snapshot current = visibleSnapshot();
        return !source.isEmpty() && (current.updatedAt == 0 || expired(current.updatedAt, hours));
    }

    /** Background-only optional initial cache read. refresh loads the matching cache itself. */
    public void loadCached() {
        synchronized (refreshLock) {
            try {
                Snapshot loaded = readCache(null);
                if (loaded != null && (requestedSource.isEmpty() || requestedSource.equals(loaded.source))) {
                    if (requestedSource.isEmpty()) requestedSource = loaded.source;
                    snapshot = loaded;
                    status = "已加载节目单缓存";
                }
            } catch (Exception error) { status = "节目单缓存不可用"; }
            cacheExamined = true;
        }
    }

    public RefreshResult refresh(String url, int hours, boolean force) {
        String source = cleanUrl(url);
        refreshDue(source, hours);
        if (source.isEmpty()) return new RefreshResult(true, false, false, "未配置节目单", Snapshot.empty());
        synchronized (refreshLock) {
            // A newer source selection supersedes a queued request before it can replace the cache.
            if (!source.equals(requestedSource)) return result(false, false, "节目单来源已改变");
            if (!cacheExamined || !source.equals(snapshot.source)) {
                try {
                    Snapshot cached = readCache(source);
                    if (cached != null) snapshot = cached;
                } catch (Exception ignored) { }
                cacheExamined = true;
            }
            Snapshot current = visibleSnapshot();
            if (!force && current.updatedAt > 0 && !expired(current.updatedAt, hours)) {
                stale = false;
                status = "节目单缓存有效";
                return result(true, false, status);
            }
            File download = null;
            try {
                status = "正在更新节目单";
                download = File.createTempFile("xmltv-", ".xml", tempDirectory);
                download(source, download);
                Snapshot parsed;
                try (InputStream input = new FileInputStream(download)) { parsed = parse(input, source, 0, selection); }
                if (!source.equals(requestedSource)) return result(false, false, "节目单来源已改变");
                long updated = System.currentTimeMillis();
                // Timestamp and XML live in one atomic file; a failed write never advances the timestamp.
                if (!parsed.selectionFingerprint.equals(selection.fingerprint)) return result(false, false, "节目单匹配配置已改变");
                writeFilteredXml(parsed, download);
                save(download, source, updated, parsed.selectionFingerprint);
                if (!source.equals(requestedSource) || !parsed.selectionFingerprint.equals(selection.fingerprint)) return result(false, false, "节目单来源或匹配配置已改变");
                parsed = new Snapshot(source, updated, parsed.programs, parsed.aliases, parsed.ids, parsed.programmeCount, parsed.channelNames, parsed.selectionFingerprint);
                snapshot = parsed;
                stale = false;
                status = parsed.programmeCount == 0 ? "来源未提供可用节目" : "节目单已更新";
                return result(true, true, status);
            } catch (Exception error) {
                if (!source.equals(requestedSource)) return result(false, false, "节目单来源已改变");
                stale = visibleSnapshot().updatedAt > 0;
                status = stale ? "更新失败，使用上次节目单" : "节目单更新失败";
                return result(false, false, status + "：" + message(error));
            } finally { if (download != null) download.delete(); }
        }
    }

    private RefreshResult result(boolean success, boolean changed, String message) {
        return new RefreshResult(success, changed, stale, message, visibleSnapshot());
    }

    private Snapshot visibleSnapshot() {
        Snapshot data = snapshot;
        return requestedSource.equals(data.source) && selection.fingerprint.equals(data.selectionFingerprint) ? data : Snapshot.empty();
    }

    private static boolean expired(long updated, int hours) {
        long age = System.currentTimeMillis() - updated;
        long ttl = Math.max(1L, Math.min(hours, 24 * 30)) * 3600000L;
        return age < 0 || age >= ttl;
    }

    private static String cleanUrl(String url) { return url == null ? "" : url.trim(); }
    private static String message(Exception error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private Snapshot readCache(String expectedSource) throws IOException, XmlPullParserException {
        if (!cache.getBaseFile().exists() && !new File(cache.getBaseFile().getPath() + ".bak").exists()) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(cache.openRead()))) {
            if (!MAGIC.equals(input.readUTF())) throw new IOException("无效缓存格式");
            String source = input.readUTF();
            String fingerprint = input.readUTF();
            Selection rules = selection;
            long updated = input.readLong();
            if (!rules.fingerprint.equals(fingerprint)) return null;
            if (expectedSource != null && !expectedSource.equals(source)) return null;
            if (updated <= 0 || source.isEmpty()) throw new IOException("无效缓存时间");
            return parse(new LimitedInputStream(input, MAX_XML), source, updated, rules);
        }
    }

    private void save(File xml, String source, long updated) throws IOException {
        save(xml, source, updated, selection.fingerprint);
    }
    private void save(File xml, String source, long updated, String fingerprint) throws IOException {
        FileOutputStream output = null;
        try {
            output = cache.startWrite();
            DataOutputStream header = new DataOutputStream(output);
            header.writeUTF(MAGIC); header.writeUTF(source); header.writeUTF(fingerprint); header.writeLong(updated); header.flush();
            try (InputStream input = new FileInputStream(xml)) { copy(input, output, MAX_XML); }
            cache.finishWrite(output);
            output = null;
            try (DataInputStream check = new DataInputStream(new BufferedInputStream(cache.openRead()))) {
                if (!MAGIC.equals(check.readUTF()) || !source.equals(check.readUTF()) || !fingerprint.equals(check.readUTF()) || updated != check.readLong()) throw new IOException("节目单缓存未能完整保存");
            }
        } catch (IOException error) {
            if (output != null) cache.failWrite(output);
            throw error;
        }
    }

    /** Persist only matched IDs and their real programmes, not the full provider catalogue. */
    private static void writeFilteredXml(Snapshot data, File target) throws IOException, XmlPullParserException {
        try (OutputStream output = new LimitedOutputStream(new BufferedOutputStream(new FileOutputStream(target)), MAX_XML)) {
            XmlSerializer xml = XmlPullParserFactory.newInstance().newSerializer();
            xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true); xml.startTag(null, "tv");
            SimpleDateFormat date = new SimpleDateFormat("yyyyMMddHHmmss '+0000'", Locale.ROOT);
            date.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (String id : new TreeSet<>(data.ids)) {
                xml.startTag(null, "channel"); xml.attribute(null, "id", id);
                List<String> names = data.channelNames.get(id);
                if (names != null) for (String name : names) {
                    xml.startTag(null, "display-name"); xml.text(name); xml.endTag(null, "display-name");
                }
                xml.endTag(null, "channel");
            }
            for (String id : new TreeSet<>(data.programs.keySet())) for (Program program : data.programs.get(id)) {
                xml.startTag(null, "programme"); xml.attribute(null, "channel", id);
                xml.attribute(null, "start", date.format(new Date(program.start)));
                xml.attribute(null, "stop", date.format(new Date(program.end)));
                xml.startTag(null, "title"); xml.text(program.title); xml.endTag(null, "title");
                if (!program.description.isEmpty()) { xml.startTag(null, "desc"); xml.text(program.description); xml.endTag(null, "desc"); }
                xml.endTag(null, "programme");
            }
            xml.endTag(null, "tv"); xml.endDocument(); xml.flush();
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long count;
        LimitedOutputStream(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws IOException { if (++count > limit) throw new IOException("节目单缓存过大"); out.write(value); }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            count += length; if (count > limit) throw new IOException("节目单缓存过大"); out.write(data, offset, length);
        }
    }
    private static void download(String source, File output) throws IOException {
        URL url = new URL(source);
        HttpURLConnection connection = null;
        for (int redirect = 0; redirect <= 4; redirect++) {
            if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("仅支持 HTTP/HTTPS XMLTV 地址");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("Accept", "application/xml,text/xml,application/gzip,*/*");
            connection.setRequestProperty("User-Agent", "WebViewTV-Research/0.3 XMLTV");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirect == 4) throw new IOException("节目单重定向无效");
                url = new URL(url, location);
                continue;
            }
            if (code < 200 || code >= 300) { connection.disconnect(); throw new IOException("HTTP " + code); }
            break;
        }
        if (connection == null) throw new IOException("节目单连接失败");
        try {
            long length = connection.getContentLengthLong();
            if (length > MAX_DOWNLOAD) throw new IOException("节目单下载超过 16 MB");
            try (InputStream raw = new LimitedInputStream(connection.getInputStream(), MAX_DOWNLOAD);
                 PushbackInputStream sniff = new PushbackInputStream(new BufferedInputStream(raw), 2);
                 OutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
                int first = sniff.read(), second = sniff.read();
                if (second >= 0) sniff.unread(second);
                if (first >= 0) sniff.unread(first);
                // Sniff bytes, not suffix/header: .gz + HTTP gzip must not be decompressed twice.
                if (first == 0x1f && second == 0x8b) {
                    try (InputStream decoded = new GZIPInputStream(sniff)) { copy(decoded, target, MAX_XML); }
                } else copy(sniff, target, MAX_XML);
            }
        } finally { connection.disconnect(); }
    }

    private static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] bytes = new byte[16384];
        long total = 0;
        int length;
        while ((length = input.read(bytes)) != -1) {
            total += length;
            if (total > limit) throw new IOException("节目单解压超过 64 MB");
            output.write(bytes, 0, length);
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        private void check(int read) throws IOException {
            if (read > 0) count += read;
            if (count > limit) throw new IOException("节目单数据过大");
        }
        @Override public int read() throws IOException { int value = in.read(); check(value < 0 ? 0 : 1); return value; }
        @Override public int read(byte[] data, int offset, int length) throws IOException { int read = in.read(data, offset, length); check(read); return read; }
    }

    private static Snapshot parse(InputStream input, String source, long updated) throws IOException, XmlPullParserException {
        return parse(input, source, updated, Selection.all());
    }
    private static Snapshot parse(InputStream input, String source, long updated, Selection rules) throws IOException, XmlPullParserException {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false); } catch (XmlPullParserException unsupported) { /* KXml2 has no DTD processing feature. */ }
        // nextToken rejects every DOCTYPE before content is consumed. External entities are never resolved.
        parser.setInput(input, null);
        Map<String, List<String>> names = new HashMap<>();
        Map<String, List<RawProgram>> raw = new HashMap<>();
        int total = 0, channelCount = 0;
        long parseNow = System.currentTimeMillis();
        long windowStart = parseNow - 2L * 86400000L, windowEnd = parseNow + 8L * 86400000L;
        boolean rootSeen = false, rootClosed = false;
        Set<String> retainedIds = new HashSet<>(rules.ids);
        int event;
        while ((event = safeNext(parser)) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == 1 && "tv".equals(parser.getName())) rootClosed = true;
            if (event != XmlPullParser.START_TAG) continue;
            if (parser.getDepth() == 1) {
                if (!"tv".equals(parser.getName()) || rootSeen) throw new IOException("不是 XMLTV 节目单");
                rootSeen = true;
            } else if (rootSeen && parser.getDepth() == 2 && "channel".equals(parser.getName())) {
                if (++channelCount > MAX_CHANNELS) throw new IOException("节目单频道过多");
                String id = attribute(parser, "id");
                List<String> labels = new ArrayList<>();
                int depth = parser.getDepth();
                while ((event = safeNext(parser)) != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
                    if (event == XmlPullParser.START_TAG && parser.getDepth() == depth + 1) {
                        if ("display-name".equals(parser.getName())) labels.add(readText(parser, 256));
                        else skip(parser);
                    }
                }
                if (!id.isEmpty() && (rules.all || retainedIds.contains(id) || rules.matches(labels))) {
                    names.put(id, labels); retainedIds.add(id);
                }
            } else if (rootSeen && parser.getDepth() == 2 && "programme".equals(parser.getName())) {
                if (++total > MAX_PROGRAMMES) throw new IOException("节目单节目超过 100000 条");
                String id = attribute(parser, "channel");
                if (!rules.all && !retainedIds.contains(id)) { skip(parser); continue; }
                String stop = attribute(parser, "stop");
                long start = parseTime(attribute(parser, "start")), end = parseTime(stop);
                // Keep useful recent/current/upcoming listings, not stale provider archives.
                if (start > windowEnd || (end > 0 && end < windowStart) || (end <= 0 && start < windowStart)) {
                    skip(parser); continue;
                }
                String title = "", description = "";
                int depth = parser.getDepth();
                while ((event = safeNext(parser)) != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
                    if (event == XmlPullParser.START_TAG && parser.getDepth() == depth + 1) {
                        if ("title".equals(parser.getName())) { String value = readText(parser, 500); if (title.isEmpty()) title = value; }
                        else if ("desc".equals(parser.getName())) { String value = readText(parser, 4000); if (description.isEmpty()) description = value; }
                        else skip(parser);
                    }
                }
                if (!id.isEmpty() && start > 0 && !title.isEmpty() && (stop.isEmpty() || end > start)) {
                    List<RawProgram> list = raw.get(id);
                    if (list == null) { list = new ArrayList<>(); raw.put(id, list); }
                    list.add(new RawProgram(start, end, title, description));
                }
            } else skip(parser);
        }
        if (!rootSeen || !rootClosed) throw new IOException("XMLTV 文件不完整");
        Map<String, List<Program>> programs = new HashMap<>();
        Set<String> ids = new HashSet<>(names.keySet());
        ids.addAll(raw.keySet());
        int accepted = 0;
        for (Map.Entry<String, List<RawProgram>> entry : raw.entrySet()) {
            List<RawProgram> values = entry.getValue();
            Collections.sort(values, (a, b) -> Long.compare(a.start, b.start));
            List<Program> complete = new ArrayList<>();
            // A reverse pass is linear even when an XMLTV source repeats the same start time.
            long previousStart = 0, nextDistinctStart = 0;
            for (int i = values.size() - 1; i >= 0; i--) {
                RawProgram item = values.get(i);
                if (item.start != previousStart) { nextDistinctStart = previousStart; previousStart = item.start; }
                if (item.end <= 0 && nextDistinctStart > item.start) item.end = nextDistinctStart;
            }
            for (RawProgram item : values) {
                if (item.end > item.start && item.end >= windowStart && item.start <= windowEnd)
                    complete.add(new Program(item.start, item.end, item.title, item.description));
            }
            if (!complete.isEmpty()) {
                accepted += complete.size();
                programs.put(entry.getKey(), Collections.unmodifiableList(complete));
            }
        }
        Map<String, String> aliases = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : names.entrySet()) {
            for (String name : entry.getValue()) {
                for (String alias : aliasesFor(name)) {
                    if (alias.isEmpty() || ambiguous.contains(alias)) continue;
                    String existing = aliases.get(alias);
                    if (existing != null && !existing.equals(entry.getKey())) { aliases.remove(alias); ambiguous.add(alias); }
                    else aliases.put(alias, entry.getKey());
                }
            }
        }
        return new Snapshot(source, updated, programs, aliases, ids, accepted, names, rules.fingerprint);
    }

    private static String attribute(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null || value.length() > 512 ? "" : value.trim();
    }

    private static int safeNext(XmlPullParser parser) throws IOException, XmlPullParserException {
        int event = parser.nextToken();
        if (event == XmlPullParser.DOCDECL) throw new IOException("节目单不能包含 DOCTYPE");
        if (event == XmlPullParser.ENTITY_REF && parser.getText() == null) throw new IOException("节目单不能包含外部实体");
        return event;
    }

    private static String readText(XmlPullParser parser, int limit) throws IOException, XmlPullParserException {
        int depth = parser.getDepth(), event;
        StringBuilder value = new StringBuilder();
        while ((event = safeNext(parser)) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) {
                String text = parser.getText();
                if (text != null && value.length() < limit) value.append(text, 0, Math.min(text.length(), limit - value.length()));
            }
        }
        return value.toString().trim();
    }

    private static void skip(XmlPullParser parser) throws IOException, XmlPullParserException {
        int depth = parser.getDepth(), event;
        while ((event = safeNext(parser)) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
        }
    }

    /** XMLTV timestamps with a numeric offset, UTC/Z/GMT, or absent zone (Asia/Shanghai). */
    public static long parseTime(String value) {
        if (value == null) return 0;
        Matcher match = DATE.matcher(value.trim());
        if (!match.matches()) return 0;
        String digits = match.group(1);
        while (digits.length() < 14) digits += "0";
        String offset = match.group(2);
        TimeZone zone;
        if (offset == null) zone = TimeZone.getTimeZone("Asia/Shanghai");
        else if (offset.equalsIgnoreCase("Z") || offset.equalsIgnoreCase("UTC") || offset.equalsIgnoreCase("GMT")) zone = TimeZone.getTimeZone("UTC");
        else {
            String compact = offset.replace(":", "");
            int hours = Integer.parseInt(compact.substring(1, 3)), minutes = Integer.parseInt(compact.substring(3, 5));
            if (hours > 23 || minutes > 59) return 0;
            zone = TimeZone.getTimeZone("GMT" + compact.substring(0, 3) + ":" + compact.substring(3));
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        format.setLenient(false); format.setTimeZone(zone);
        try { return format.parse(digits).getTime(); } catch (ParseException error) { return 0; }
    }

    private static List<String> aliasesFor(String name) {
        String normalized = normalize(name);
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (!normalized.isEmpty()) aliases.add(normalized);
        Matcher cctv = CCTV.matcher(normalized);
        if (cctv.matches()) {
            int number = chineseNumber(cctv.group(1));
            String rest = cctv.group(2);
            if (number > 0 && !rest.startsWith("K")) {
                String suffix = number == 5 && (rest.startsWith("+") || rest.startsWith("PLUS") || rest.startsWith("体育赛事")) ? "PLUS" : "";
                aliases.add("CCTV" + number + suffix);
            }
        }
        if (normalized.endsWith("卫视") && normalized.length() > 2) aliases.add(normalized.substring(0, normalized.length() - 2));
        return new ArrayList<>(aliases);
    }

    private static String normalize(String name) {
        if (name == null) return "";
        String value = Normalizer.normalize(name, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        value = value.replaceAll("[\\s\\p{Z}\\-_.·/()\\[\\]（）]", "");
        value = value.replaceAll("(?:高清|标清|超清|频道|HD|SD)$", "");
        value = value.replaceAll("(?:高清|标清|超清|HD|SD)$", "");
        return value;
    }

    private static int chineseNumber(String text) {
        try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { }
        if (text.equals("十")) return 10;
        int ten = text.indexOf('十');
        if (ten >= 0) {
            int first = ten == 0 ? 1 : digit(text.charAt(0));
            int second = ten == text.length() - 1 ? 0 : digit(text.charAt(ten + 1));
            return first * 10 + second;
        }
        return text.length() == 1 ? digit(text.charAt(0)) : 0;
    }

    private static int digit(char value) { return "一二三四五六七八九".indexOf(value) + 1; }

    private String resolve(JSONObject channel, Snapshot data) {
        if (channel == null) return null;
        String explicit = channel.optString("epgId", "").trim();
        if (!explicit.isEmpty()) return data.ids.contains(explicit) ? explicit : null;
        for (String alias : aliasesFor(channel.optString("name", ""))) {
            String id = data.aliases.get(alias);
            if (id != null) return id;
        }
        return null;
    }

    public NowNext lookup(JSONObject channel, long nowMillis) {
        Snapshot data = visibleSnapshot();
        String id = resolve(channel, data);
        List<Program> programs = id == null ? null : data.programs.get(id);
        Program current = null, next = null;
        if (programs != null) {
            int index = firstAfterStart(programs, nowMillis);
            for (int i = index - 1; i >= 0; i--) {
                Program item = programs.get(i);
                if (item.start <= nowMillis && nowMillis < item.end) { current = item; break; }
                if (nowMillis - item.start > 2L * 86400000L) break;
            }
            if (index < programs.size()) next = programs.get(index);
        }
        return new NowNext(current, next, nowMillis, stale);
    }

    /** Current plus upcoming entries before the end of tomorrow in China, at most 20. */
    public List<Program> schedule(JSONObject channel, long nowMillis) {
        Snapshot data = visibleSnapshot();
        String id = resolve(channel, data);
        List<Program> programs = id == null ? null : data.programs.get(id);
        if (programs == null) return Collections.emptyList();
        Calendar endOfTomorrow = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        endOfTomorrow.setTimeInMillis(nowMillis);
        endOfTomorrow.set(Calendar.HOUR_OF_DAY, 0); endOfTomorrow.set(Calendar.MINUTE, 0);
        endOfTomorrow.set(Calendar.SECOND, 0); endOfTomorrow.set(Calendar.MILLISECOND, 0);
        endOfTomorrow.add(Calendar.DATE, 2);
        long limit = endOfTomorrow.getTimeInMillis();
        ArrayList<Program> result = new ArrayList<>();
        NowNext now = lookup(channel, nowMillis);
        if (now.current != null) result.add(now.current);
        int index = firstAfterStart(programs, nowMillis);
        for (int i = index; i < programs.size() && result.size() < 20; i++) {
            Program item = programs.get(i);
            if (item.start >= limit) break;
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static int firstAfterStart(List<Program> programs, long now) {
        int left = 0, right = programs.size();
        while (left < right) {
            int middle = (left + right) >>> 1;
            if (programs.get(middle).start <= now) left = middle + 1;
            else right = middle;
        }
        return left;
    }
}
