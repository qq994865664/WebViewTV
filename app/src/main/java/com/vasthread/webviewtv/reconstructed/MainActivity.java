package com.vasthread.webviewtv.reconstructed;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.concurrent.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.TextUtils;
import android.util.Rational;
import android.util.TypedValue;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Independent native Android reconstruction from the supplied APK's observable contracts. */
public class MainActivity extends Activity {
    private static final String TAG = "WVTResearch";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private final ArrayList<JSONObject> channels = new ArrayList<>();
    private final HashSet<String> favorites = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private ConfigRepository configRepository;
    private EpgRepository epgRepository;
    private SettingsView settingsView;
    private EpgView epgView;
    private volatile JSONObject config = new JSONObject(), epgMapping = new JSONObject();
    private String channelStatus = "", epgStatus = "";
    private String mainScript = "", scriptPrefix = "", scriptSuffix = "";
    private volatile boolean fastSwitch = true, rememberSource = true;
    private volatile long playbackToken;
    private volatile int documentSequence;
    private long switchStarted;
    private boolean firstFrameReported, channelUpdating, epgUpdating, destroyed;
    private static final int IMPORT_CONFIG = 71, EXPORT_CONFIG = 72;
    private final Runnable refreshTick = () -> {
        updateProgramme();
        refreshRepositories(false, false);
        handler.postDelayed(this.refreshTickRunner(), 60000);
    };
    private Runnable refreshTickRunner() { return refreshTick; }
    private JSONArray adapters = new JSONArray();
    private SharedPreferences prefs;
    private WebView web;
    private FrameLayout root, playbackStage, fullscreen;
    private TvMenuView menu;
    private PlayerControlsView playerControls;
    private ChannelSwipeAnimation channelSwipeAnimation;
    private LinearLayout hud;
    private TextView status;
    private ProgressBar progress;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int channelIndex, sourceIndex, sourceCount, pageSequence;
    private volatile String adapterJson = "{}", extra = "";
    private String digits = "", injectionScript = "";
    private boolean pageInjected, pageInjectionAttempted, videoReady, buffering, viewportFullscreen;
    private long lastPlaybackKey;
    private final SourceFailover sourceFailover = new SourceFailover();
    private int sourceTimeoutSeconds = 15;
    private boolean playbackPaused;
    private final Runnable sourceWatchdog = this::checkSourceTimeout;
    private int videoWidth = 16, videoHeight = 9;
    private final Runnable hideHud = () -> { if (hud != null) hud.setVisibility(View.GONE); };
    private final Runnable noVideoHint = () -> {
        if (!videoReady) showHud("暂未开始播放 · 左右切源 / 菜单刷新", false, 6000);
    };
    private final Runnable selectNumber = () -> {
        try {
            int number = Integer.parseInt(digits);
            if (number > 0 && number <= channels.size()) play(number - 1, -999);
            else showHud("没有这个频道 · " + digits, false, 2400);
        } catch (NumberFormatException ignored) { }
        digits = "";
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        loadConfig();
        if (channels.isEmpty()) { finish(); return; }
        createUi();
        immersive();
        int last = Math.min(prefs.getInt("last", 0), channels.size() - 1);
        String lastId = prefs.getString("lastChannelId", "");
        for (int i = 0; i < channels.size(); i++) if (channelKey(channels.get(i)).equals(lastId)) { last = i; break; }
        play(last, -999);
        refreshRepositories(false, false);
        handler.postDelayed(refreshTick, 60000);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(5894);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private String asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int length;
            while ((length = in.read(bytes)) != -1) out.write(bytes, 0, length);
            return out.toString("UTF-8");
        }
    }

    private void loadConfig() {
        try {
            configRepository = new ConfigRepository(this);
            epgRepository = new EpgRepository(this);
            try { epgMapping = new JSONObject(asset("epg-mapping.json")); } catch (Exception ignored) { }
            config = enableRepositorySourceOnce(configRepository.load());
            boolean mapped = applyMappings(config);
            String configNotice = configRepository.lastError();
            String initialWarning = configRepository.lastWarning();
            if (mapped && configNotice.isEmpty()) configRepository.save(config);
            readChannels(configRepository.channels());
            channelStatus = configNotice;
            if (channelStatus.isEmpty()) channelStatus = initialWarning;
            if (!channelStatus.isEmpty()) toast(channelStatus);
            adapters = new JSONArray(asset("webpage_adapter.json"));
            scriptPrefix = asset("js/base.js") + "\n" + asset("js/platform_x5.js").replace("window.main.reportVideoLoadingState(isLoading);", "window.main.reportVideoLoadingStateForToken(window.__wvtPlaybackToken,isLoading);") + "\n";
            mainScript = asset("js/main.js");
            scriptSuffix = "\n" + asset("js/tv_fullscreen.js").replace("window.main.reportVideoLoadingState(false);", "window.main.reportVideoLoadingStateForToken(window.__wvtPlaybackToken,false);").replace("window.main.reportViewportFullscreen(viewportWidth, viewportHeight, videoWidth, videoHeight);", "window.main.reportViewportFullscreenForToken(window.__wvtPlaybackToken,viewportWidth,viewportHeight,videoWidth,videoHeight);") + "\n" + asset("js/fast_switch.js");
            scriptSuffix += "\n" + asset("js/playback_health.js");
            applyPlaybackSettings();
        } catch (Exception error) {
            android.util.Log.e(TAG, "Configuration or assets failed", error);
            toast("配置加载失败: " + error.getMessage());
        }
    }

    /** One upgrade migration; an address cleared afterwards remains a manual-only choice. */
    private JSONObject enableRepositorySourceOnce(JSONObject loaded) {
        String migrationKey = "githubChannelSourceMigratedV1";
        if (prefs.getBoolean(migrationKey, false) || !configRepository.lastError().isEmpty()) return loaded;
        try {
            if (loaded.optString("channelSourceUrl", "").isEmpty()) {
                loaded.put("channelSourceUrl", ConfigRepository.DEFAULT_CHANNEL_URL);
                configRepository.save(loaded);
                android.util.Log.i(TAG, "Channel source migrated to repository");
            }
            prefs.edit().putBoolean(migrationKey, true).apply();
            return configRepository.snapshot();
        } catch (Exception error) {
            android.util.Log.w(TAG, "Repository source migration failed; keeping saved settings", error);
            toast("仓库频道地址保存失败，继续使用本机配置");
            return configRepository.snapshot();
        }
    }

    private void readChannels(JSONArray data) {
        channels.clear(); favorites.clear();
        for (int i = 0; i < data.length(); i++) {
            JSONObject channel = data.optJSONObject(i);
            if (channel == null) continue;
            channels.add(channel);
            if (prefs.getBoolean("favorite:" + channel.optString("name"), false)) favorites.add(channel.optString("name"));
        }
    }

    private boolean applyMappings(JSONObject target) throws JSONException {
        JSONObject epg = target.optJSONObject("epg");
        if (epg == null || !epg.optString("url").contains("free-epg.de/")) return false;
        boolean changed = false;
        JSONArray data = target.optJSONArray("channels");
        if (data == null) return false;
        for (int i = 0; i < data.length(); i++) {
            JSONObject channel = data.optJSONObject(i);
            if (channel != null && channel.optString("epgId").isEmpty()) {
                String id = epgMapping.optString(channel.optString("group") + ":" + channel.optString("name"), epgMapping.optString(channel.optString("name")));
                if (!id.isEmpty()) { channel.put("epgId", id); changed = true; }
            }
        }
        return changed;
    }

    private void applyPlaybackSettings() {
        JSONObject playback = config.optJSONObject("playback");
        fastSwitch = playback == null || playback.optBoolean("fastSwitch", true);
        rememberSource = playback == null || playback.optBoolean("rememberSource", true);
        sourceTimeoutSeconds = playback == null ? 15 : playback.optInt("sourceTimeoutSeconds", 15);
        sourceFailover.configure(sourceTimeoutSeconds);
        sourceFailover.start(sourceCount, sourceIndex, SystemClock.elapsedRealtime(), false);
        armSourceWatchdog();
        injectionScript = scriptPrefix + (fastSwitch ? mainScript.replace("wvt_loop() }, 1000)", "wvt_loop() }, 150)") : mainScript) + scriptSuffix;
    }

    private int px(int reference) {
        return Math.max(1, Math.round(reference * UiScale.landscape(this, 960)));
    }

    private void createUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        playbackStage = new FrameLayout(this);
        root.addView(playbackStage, new FrameLayout.LayoutParams(-1, -1));
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            // Some sites scroll to their former player box after loading. The TV player stays at the origin.
            if (viewportFullscreen && (x != 0 || y != 0)) web.scrollTo(0, 0);
        });
        playbackStage.addView(web, new FrameLayout.LayoutParams(-1, -1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "main");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                pageSequence++;
                documentSequence = pageSequence;
                viewportFullscreen = false;
                pageInjected = false;
                pageInjectionAttempted = false;
                videoReady = false;
                android.util.Log.d(TAG, "page started " + url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                android.util.Log.d(TAG, "page finished " + url);
                if (!videoReady) showHud("正在准备播放器", true, 0);
                inject();
            }
            @Override public void onPageCommitVisible(WebView view, String url) { if (fastSwitch) inject(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    handler.removeCallbacks(noVideoHint);
                    showHud("网页加载失败 · 左右切源 / 菜单刷新", false, 8000);
                    android.util.Log.w(TAG, "main page error " + error.getDescription());
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { if (value >= (fastSwitch ? 30 : 80)) inject(); }
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.d(TAG, message.messageLevel() + " " + message.message());
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                hideFullscreen();
                fullscreenCallback = callback;
                fullscreen = new FrameLayout(MainActivity.this);
                fullscreen.setBackgroundColor(Color.BLACK);
                fullscreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
                playbackStage.addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
                playerControls.bringToFront();
                hud.bringToFront();
                menu.bringToFront();
                if (settingsView != null && settingsView.isOpen()) settingsView.bringToFront();
                if (epgView != null && epgView.isOpen()) epgView.bringToFront();
                immersive();
            }
            @Override public void onHideCustomView() { hideFullscreen(); }
        });

        // Keep tap and remote controls in the native layer, including HTML custom fullscreen.
        channelSwipeAnimation = new ChannelSwipeAnimation(this, playbackStage);
        root.addView(channelSwipeAnimation, new FrameLayout.LayoutParams(-1, -1));
        playerControls = new PlayerControlsView(this, new PlayerControlsView.Listener() {
            public void onChannelSwipe(int step) { swipePlayback(false, step); }
            public void onSourceSwipe(int step) { swipePlayback(true, step); }
            public void onChannelDrag(float distance, boolean ready) {
                if (!canSwipePlayback() || channels.isEmpty()) return;
                int next = Math.floorMod(channelIndex + (distance < 0 ? 1 : -1), channels.size());
                channelSwipeAnimation.drag(distance, ready, channels.get(next).optString("name"));
            }
            public void onChannelDragCancelled() { channelSwipeAnimation.cancelDrag(); }
        });
        playerControls.setBackgroundColor(Color.TRANSPARENT);
        playerControls.setFocusable(true);
        playerControls.setContentDescription("点击打开频道菜单，上下滑动换台，左右滑动切源");
        playerControls.setOnClickListener(view -> showMenu(true));
        root.addView(playerControls, new FrameLayout.LayoutParams(-1, -1));
        hud = new LinearLayout(this);
        hud.setGravity(Gravity.CENTER_VERTICAL);
        hud.setPadding(px(16), 0, px(18), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEE182438);
        background.setCornerRadius(px(14));
        background.setStroke(px(1), 0xFF35435C);
        hud.setBackground(background);
        progress = new ProgressBar(this);
        LinearLayout.LayoutParams indicator = new LinearLayout.LayoutParams(px(24), px(24));
        indicator.rightMargin = px(12);
        hud.addView(progress, indicator);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18));
        status.setTextColor(0xFFF4F7FF);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setMaxWidth(px(740));
        hud.addView(status, new LinearLayout.LayoutParams(-2, -1));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(-2, px(54), Gravity.BOTTOM | Gravity.LEFT);
        hudParams.setMargins(px(28), 0, px(28), px(28));
        root.addView(hud, hudParams);
        rebuildMenu();
        settingsView = new SettingsView(this, new SettingsView.Listener() {
            public void onSave(JSONObject settings) { saveSettings(settings); }
            public void onImportChannels() { importConfig(); }
            public void onExportConfig() { exportConfig(); }
            public void onRefreshChannels() { refreshRepositories(true, false); }
            public void onRefreshEpg() { refreshRepositories(false, true); }
            public void onEditChannels() { editChannels(); }
            public void onResetDefaults() { resetDefaults(); }
            public void onClose() { closeSettings(); }
        });
        root.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));
        epgView = new EpgView(this, () -> { epgView.close(); showMenu(true); });
        root.addView(epgView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void rebuildMenu() {
        boolean wasOpen = menu != null && menu.isOpen();
        if (menu != null) root.removeView(menu);
        menu = new TvMenuView(this, channels, new TvMenuView.Listener() {
            public void onChannelSelected(int index) { play(index, -999); }
            public void onSource() { switchSource(1); }
            public void onFavorite() { favorite(); }
            public void onRefresh() { refresh(); }
            public void onPip() { pip(); }
            public void onSettings() { openSettings(); }
            public void onEpg() { openProgramme(); }
            public void onClose() { showMenu(false); }
        });
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        syncMenu(); updateProgramme();
        if (wasOpen) menu.open();
        if (settingsView != null && settingsView.isOpen()) settingsView.bringToFront();
        if (epgView != null && epgView.isOpen()) epgView.bringToFront();
    }

    private void syncMenu() { menu.setCurrentChannel(channelIndex, sourceIndex, sourceCount, favorites); updateProgramme(); }

    private void favorite() {
        if (channels.isEmpty()) return;
        String name = channels.get(channelIndex).optString("name");
        boolean added = !favorites.remove(name);
        if (added) favorites.add(name);
        prefs.edit().putBoolean("favorite:" + name, added).apply();
        syncMenu();
        toast(added ? "已收藏 " + name : "已取消收藏 " + name);
    }

    private void play(int index, int source) {
        resetPlaybackSwipe();
        playInternal(index, source);
    }

    private void playInternal(int index, int source) {
        playInternal(index, source, false);
    }

    private void playInternal(int index, int source, boolean retry) {
        if (channels.isEmpty()) return;
        int nextIndex = Math.floorMod(index, channels.size());
        JSONObject channel = channels.get(nextIndex);
        JSONArray urls = channel.optJSONArray("urls");
        if (urls == null || urls.length() == 0) { toast("频道没有播放源"); return; }
        hideFullscreen();
        channelIndex = nextIndex;
        sourceCount = urls.length();
        if (source == -999) {
            source = 0;
            if (rememberSource) {
                String successful = prefs.getString("source:" + channelKey(channel), "");
                for (int i = 0; i < urls.length(); i++) if (urlOf(urls, i).equals(successful)) { source = i; break; }
            }
        }
        sourceIndex = Math.floorMod(source, sourceCount);
        JSONObject item = urls.optJSONObject(sourceIndex);
        String url = item == null ? urls.optString(sourceIndex) : item.optString("url");
        extra = item == null ? "" : item.optString("extra", "");
        JSONObject adapter = defaultAdapter();
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject match = adapters.optJSONObject(i);
            if (match != null && !match.optString("url").isEmpty() && url.contains(match.optString("url"))) {
                Iterator<String> keys = match.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try { adapter.put(key, match.get(key)); } catch (JSONException ignored) { }
                }
                break;
            }
        }
        adapterJson = adapter.toString();
        String ua = adapter.optString("ua", "PC");
        String requestedUa = "PC".equals(ua) ? PC_UA : "MOBILE".equals(ua) ? WebSettings.getDefaultUserAgent(this) : ua;
        if (!requestedUa.equals(web.getSettings().getUserAgentString())) web.getSettings().setUserAgentString(requestedUa);
        prefs.edit().putInt("last", channelIndex).putString("lastChannelId", channelKey(channel)).apply();
        syncMenu();
        showMenu(false);
        beginLoading(retry);
        android.util.Log.i(TAG, "play channel=" + (channelIndex + 1) + " source=" + (sourceIndex + 1));
        viewportFullscreen = false;
        web.scrollTo(0, 0);
        web.stopLoading();
        web.loadUrl(url);
    }

    private JSONObject defaultAdapter() {
        JSONObject adapter = new JSONObject();
        try {
            adapter.put("videoElementCondition", "e.tagName === 'VIDEO'");
            for (String key : new String[]{"needPreClick", "needPreClick2", "needPlayButton", "needEnterFullscreenButton"}) adapter.put(key, false);
            for (String key : new String[]{"preClickElementCondition", "preClickElementCondition2", "playButtonCondition", "enterFullscreenButtonCondition"}) adapter.put(key, "false");
            adapter.put("ua", "PC");
        } catch (JSONException ignored) { }
        return adapter;
    }

    private void beginLoading() {
        beginLoading(false);
    }

    private void beginLoading(boolean retry) {
        videoReady = false;
        firstFrameReported = false;
        switchStarted = SystemClock.elapsedRealtime();
        playbackToken++;
        buffering = true;
        handler.removeCallbacks(noVideoHint);
        showHud("加载中", true, 0);
        handler.postDelayed(noVideoHint, 18000);
        sourceFailover.start(sourceCount, sourceIndex, SystemClock.elapsedRealtime(), retry);
        armSourceWatchdog();
    }

    private void armSourceWatchdog() {
        handler.removeCallbacks(sourceWatchdog);
        if (!destroyed && !playbackPaused && sourceTimeoutSeconds > 0) handler.postDelayed(sourceWatchdog, 500);
    }

    private void checkSourceTimeout() {
        if (destroyed || playbackPaused || sourceTimeoutSeconds == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (!hasWindowFocus() || menu == null || menu.isOpen() || settingsView.isOpen() || epgView.isOpen()) {
            sourceFailover.defer(now);
        } else {
            int next = sourceFailover.next(now);
            if (next >= 0) {
                android.util.Log.i(TAG, "source-timeout channel=" + (channelIndex + 1) + " from=" + (sourceIndex + 1) + " to=" + (next + 1));
                resetPlaybackSwipe();
                handler.removeCallbacks(selectNumber); digits = "";
                playInternal(channelIndex, next, true);
                showHud("播放超时 · 已自动切换直播源", true, 0);
            } else if (next == SourceFailover.EXHAUSTED) {
                handler.removeCallbacks(noVideoHint);
                showHud(sourceCount <= 1 ? "播放超时 · 当前频道没有备用源" : "所有直播源均超时 · 可手动切源或刷新重试", false, 8000);
                android.util.Log.i(TAG, "source-exhausted channel=" + (channelIndex + 1));
            }
        }
        armSourceWatchdog();
    }

    private void showHud(String message, boolean loading, long duration) {
        handler.removeCallbacks(hideHud);
        String name = channels.isEmpty() ? "WebView 电视" : String.format(Locale.ROOT, "%03d  %s · 源 %d/%d",
                channelIndex + 1, channels.get(channelIndex).optString("name"), sourceIndex + 1, sourceCount);
        status.setText(name + " · " + message);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        hud.setVisibility(isInPictureInPictureMode() ? View.GONE : View.VISIBLE);
        if (duration > 0) handler.postDelayed(hideHud, duration);
    }

    private void switchSource(int step) { play(channelIndex, sourceIndex + step); }

    private void swipePlayback(boolean source, int step) {
        if (!canSwipePlayback()) { resetPlaybackSwipe(); return; }
        handler.removeCallbacks(selectNumber);
        digits = "";
        if (source) {
            if (sourceCount <= 1) showHud("当前频道仅有一个直播源", false, 1800);
            else switchSource(step);
        } else {
            playInternal(channelIndex + step, -999);
            channelSwipeAnimation.finish();
        }
    }

    private boolean canSwipePlayback() {
        return !destroyed && !isInPictureInPictureMode() && !menu.isOpen()
                && !settingsView.isOpen() && !epgView.isOpen();
    }

    private void resetPlaybackSwipe() {
        if (playerControls != null) playerControls.cancelGesture();
        if (channelSwipeAnimation != null) channelSwipeAnimation.reset();
    }
    private void refresh() { showMenu(false); hideFullscreen(); viewportFullscreen = false; web.scrollTo(0, 0); beginLoading(); web.reload(); }

    private void inject() {
        if (destroyed || isFinishing() || "about:blank".equals(web.getUrl())) return;
        if (pageInjectionAttempted) return;
        pageInjectionAttempted = true;
        final String pageUrl = web.getUrl();
        final int sequence = pageSequence;
        try {
            if (injectionScript.isEmpty()) throw new IOException("Playback scripts unavailable");
            String wrapped = "(function(){if(!document.documentElement||!document.body)return 'pending';try{window.__wvtPlaybackToken=" + JSONObject.quote(playbackToken + ":" + documentSequence) + ";\n" + injectionScript
                    + "\nreturn window.wvt_javascriptInjected === true && !!window.wvt_adapter && typeof window.wvt_enterViewportFullscreen === 'function';"
                    + "}catch(error){console.error('[WVTResearch] injection: '+error);return false;}})()";
            web.evaluateJavascript(wrapped, result -> {
                if (sequence != pageSequence || !Objects.equals(pageUrl, web.getUrl())) return;
                if ("\"pending\"".equals(result)) {
                    pageInjectionAttempted = false;
                    handler.postDelayed(() -> { if (sequence == pageSequence) inject(); }, 100);
                    return;
                }
                pageInjected = "true".equals(result);
                if (pageInjected) android.util.Log.i(TAG, "JS injection verified " + pageUrl);
                else injectionFailed("rejected " + result, null);
            });
        } catch (Exception error) { injectionFailed("asset or evaluation error", error); }
    }

    private void injectionFailed(String reason, Exception error) {
        android.util.Log.e(TAG, "JS injection failed: " + reason, error);
        handler.removeCallbacks(noVideoHint);
        showHud("播放适配未加载 · 菜单刷新重试", false, 8000);
        toast("播放适配加载失败，请刷新重试");
    }

    private void hideFullscreen() {
        if (fullscreen == null) return;
        playbackStage.removeView(fullscreen);
        fullscreen = null;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        if (callback != null) callback.onCustomViewHidden();
    }

    private void showMenu(boolean show) {
        if (show) { resetPlaybackSwipe(); syncMenu(); menu.open(); menu.bringToFront(); }
        else { menu.close(); playerControls.requestFocus(); immersive(); }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void pip() {
        resetPlaybackSwipe();
        try {
            showMenu(false);
            hud.setVisibility(View.GONE);
            Rational ratio = new Rational(videoWidth, videoHeight);
            float value = (float)videoWidth / videoHeight;
            if (value < 0.42f || value > 2.39f) ratio = new Rational(16, 9);
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ratio).build());
        } catch (Exception error) { toast("此设备不支持画中画"); }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) handleBack();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        if (settingsView != null && settingsView.isOpen() || epgView != null && epgView.isOpen()) return super.dispatchKeyEvent(event);
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            if (event.getRepeatCount() > 0) return true;
            if (digits.length() >= 3) digits = "";
            digits += key - KeyEvent.KEYCODE_0;
            showHud("选台 " + digits, false, 0);
            handler.removeCallbacks(selectNumber);
            handler.postDelayed(selectNumber, 1200);
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) showMenu(!menu.isOpen());
            return true;
        }
        if (!menu.isOpen()) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { showMenu(true); return true; }
            if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) {
                long now = SystemClock.uptimeMillis();
                if (event.getRepeatCount() > 0 && now - lastPlaybackKey < (fastSwitch ? 250 : 350)) return true;
                lastPlaybackKey = now;
                if (key == KeyEvent.KEYCODE_DPAD_UP) play(channelIndex - 1, -999);
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN) play(channelIndex + 1, -999);
                else switchSource(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive(); else resetPlaybackSwipe();
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); resetPlaybackSwipe(); immersive(); }
    @Override protected void onPause() {
        resetPlaybackSwipe(); super.onPause();
        playbackPaused = !isInPictureInPictureMode();
        if (playbackPaused) { handler.removeCallbacks(sourceWatchdog); if (web != null) web.onPause(); }
    }
    @Override protected void onResume() {
        super.onResume(); playbackPaused = false;
        if (web != null) web.onResume();
        sourceFailover.defer(SystemClock.elapsedRealtime()); armSourceWatchdog();
    }
    @Override public void onBackPressed() { handleBack(); }
    @Override protected void onDestroy() {
        destroyed = true;
        resetPlaybackSwipe();
        handler.removeCallbacksAndMessages(null);
        workers.shutdownNow();
        if (web != null) { web.removeJavascriptInterface("main"); playbackStage.removeView(web); web.destroy(); }
        super.onDestroy();
    }

    private static String channelKey(JSONObject channel) {
        String id = channel.optString("id");
        return id.isEmpty() ? channel.optString("group") + ":" + channel.optString("name") : id;
    }
    private static String urlOf(JSONArray urls, int index) {
        if (urls == null || index < 0 || index >= urls.length()) return "";
        JSONObject item = urls.optJSONObject(index);
        return item == null ? urls.optString(index) : item.optString("url");
    }
    private String timestamp(long value) {
        if (value <= 0) return "尚未更新";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(value));
    }
    private String channelSummary() {
        String remote = config.optString("channelSourceUrl");
        String summary = remote.isEmpty() ? "本机配置 · " + channels.size() + " 个频道" : "上次更新 " + timestamp(configRepository.lastRefreshAt()) + " · " + channels.size() + " 个频道";
        return summary + (channelStatus.isEmpty() ? "" : "\n" + channelStatus);
    }
    private String epgSummary() {
        JSONObject epg = config.optJSONObject("epg");
        if (epg != null && !epg.optBoolean("enabled", true)) return "节目单已关闭";
        return "上次更新 " + timestamp(epgRepository.getLastUpdated()) + " · " + epgRepository.getChannelCount() + " 个匹配频道"
                + "\n" + (epgStatus.isEmpty() ? epgRepository.getStatus() : epgStatus);
    }
    private void openSettings() {
        handler.removeCallbacks(selectNumber); digits = "";
        showMenu(false);
        epgView.close();
        settingsView.open(configRepository.snapshot(), configRepository.file().getAbsolutePath(), channelSummary(), epgSummary());
        settingsView.bringToFront();
    }
    private void closeSettings() { settingsView.close(); showMenu(true); }
    private void updateSettingsStatus() {
        if (settingsView != null) settingsView.updateStatus(channelSummary(), epgSummary());
    }
    private void saveSettings(JSONObject settings) {
        workers.execute(() -> {
            try {
                JSONObject saved;
                synchronized (configRepository) {
                JSONObject updated = configRepository.snapshot();
                Iterator<String> keys = settings.keys();
                while (keys.hasNext()) { String key = keys.next(); updated.put(key, settings.get(key)); }
                applyMappings(updated);
                configRepository.save(updated);
                saved = configRepository.snapshot();
                }
                handler.post(() -> {
                    if (destroyed) return;
                    adoptConfig(saved);
                    settingsView.close(); showMenu(true);
                    toast("设置已保存"); refreshRepositories(false, false);
                });
            } catch (Exception error) { handler.post(() -> { if (!destroyed) toast("保存失败：" + error.getMessage()); }); }
        });
    }
    /** Rebuild only native lists. An unchanged active URL keeps playing during background updates. */
    private void adoptConfig(JSONObject updated) {
        updated = configRepository.snapshot();
        String oldKey = channels.isEmpty() ? "" : channelKey(channels.get(channelIndex));
        String oldUrl = channels.isEmpty() ? "" : urlOf(channels.get(channelIndex).optJSONArray("urls"), sourceIndex);
        String oldExtra = extra;
        config = updated;
        readChannels(updated.optJSONArray("channels"));
        int found = -1;
        for (int i = 0; i < channels.size(); i++) if (channelKey(channels.get(i)).equals(oldKey)) { found = i; break; }
        channelIndex = Math.max(0, found);
        JSONArray urls = channels.get(channelIndex).optJSONArray("urls");
        sourceIndex = 0;
        boolean sameUrl = false;
        for (int i = 0; i < urls.length(); i++) if (found >= 0 && urlOf(urls, i).equals(oldUrl) && (urls.optJSONObject(i) == null ? "" : urls.optJSONObject(i).optString("extra")).equals(oldExtra)) { sourceIndex = i; sameUrl = true; break; }
        sourceCount = urls.length();
        applyPlaybackSettings();
        epgRepository.setChannels(updated.optJSONArray("channels"));
        JSONObject epg = updated.optJSONObject("epg");
        epgRepository.refreshDue(epg.optBoolean("enabled", true) ? epg.optString("url") : "", epg.optInt("refreshHours", 6));
        rebuildMenu(); updateSettingsStatus();
        if (!sameUrl) play(channelIndex, -999);
    }
    private void refreshRepositories(boolean forceChannels, boolean forceEpg) {
        if (destroyed || configRepository == null || config.optJSONArray("channels") == null) return;
        if (!channelUpdating && (forceChannels || configRepository.refreshDue())) {
            if (config.optString("channelSourceUrl").isEmpty()) {
                if (forceChannels) { channelStatus = "未填写远程地址，请编辑或导入本机配置"; updateSettingsStatus(); }
            } else {
                channelUpdating = true; channelStatus = "正在更新频道列表"; updateSettingsStatus();
                workers.execute(() -> {
                    ConfigRepository.RefreshResult result = configRepository.refresh(forceChannels);
                    try {
                        synchronized (configRepository) {
                            JSONObject candidate = configRepository.snapshot();
                            if (applyMappings(candidate)) configRepository.save(candidate);
                        }
                    } catch (Exception error) { android.util.Log.w(TAG, "EPG mapping save", error); }
                    JSONObject saved = configRepository.snapshot();
                    handler.post(() -> {
                        if (destroyed) return;
                        channelUpdating = false;
                        channelStatus = result.error != null ? result.error : result.warning.isEmpty() ? "频道缓存已更新" : result.warning;
                        if (result.changed) adoptConfig(saved);
                        updateSettingsStatus();
                        android.util.Log.i(TAG, "channel refresh changed=" + result.changed + " count=" + result.channelCount + " status=" + channelStatus);
                        if (forceChannels) toast(channelStatus);
                    });
                });
            }
        }
        JSONObject epg = config.optJSONObject("epg");
        boolean enabled = epg != null && epg.optBoolean("enabled", true);
        String url = enabled ? epg.optString("url") : "";
        int hours = epg == null ? 6 : epg.optInt("refreshHours", 6);
        epgRepository.setChannels(config.optJSONArray("channels"));
        boolean due = epgRepository.refreshDue(url, hours);
        if (!enabled || url.isEmpty()) {
            epgStatus = enabled ? "未配置节目单地址" : "节目单已关闭";
            updateProgramme(); updateSettingsStatus();
            return;
        }
        if (!epgUpdating && (forceEpg || due)) {
            epgUpdating = true; epgStatus = "正在更新节目单"; updateSettingsStatus();
            workers.execute(() -> {
                EpgRepository.RefreshResult result = epgRepository.refresh(url, hours, forceEpg);
                handler.post(() -> {
                    if (destroyed) return;
                    epgUpdating = false; epgStatus = result.message;
                    updateProgramme(); updateSettingsStatus();
                    if (epgView != null && epgView.isOpen()) openProgramme();
                    android.util.Log.i(TAG, "EPG refresh success=" + result.success + " channels=" + result.channelCount + " programmes=" + result.programmeCount + " " + result.message);
                    if (forceEpg) toast(result.message);
                });
            });
        }
    }
    private void updateProgramme() {
        if (menu == null || epgRepository == null || channels.isEmpty()) return;
        JSONObject epg = config.optJSONObject("epg");
        boolean enabled = epg != null && epg.optBoolean("enabled", true);
        long now = System.currentTimeMillis();
        menu.setProgrammeProvider(channel -> {
            EpgRepository.NowNext programmes = epgRepository.lookup(channel, System.currentTimeMillis());
            return enabled && programmes.current != null ? "正在播出 · " + programmes.current.title : "";
        });
        if (!enabled) { menu.setProgramme("", ""); return; }
        EpgRepository.NowNext programmes = epgRepository.lookup(channels.get(channelIndex), now);
        String current = programmes.current == null ? "暂无正在播出的节目" : programmes.current.title;
        String next = programmes.next == null ? "" : new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(programmes.next.start)) + " · " + programmes.next.title;
        menu.setProgramme(current, next);
    }
    private void openProgramme() {
        if (channels.isEmpty()) return;
        showMenu(false);
        long now = System.currentTimeMillis();
        JSONObject epg = config.optJSONObject("epg");
        boolean enabled = epg != null && epg.optBoolean("enabled", true);
        epgView.open(channels.get(channelIndex).optString("name"), enabled ? epgRepository.schedule(channels.get(channelIndex), now) : Collections.emptyList(), now, epgSummary().replace("\n", " · "));
        epgView.bringToFront();
    }
    private void handleBack() {
        handler.removeCallbacks(selectNumber); digits = "";
        if (settingsView != null && settingsView.isOpen()) { closeSettings(); return; }
        if (epgView != null && epgView.isOpen()) { epgView.close(); showMenu(true); return; }
        if (menu != null && menu.isOpen()) { showMenu(false); return; }
        new AlertDialog.Builder(this).setTitle("退出 WebView 电视？").setMessage("退出后将停止播放。")
                .setNegativeButton("继续观看", (dialog, which) -> immersive())
                .setPositiveButton("退出", (dialog, which) -> {
                    web.stopLoading(); hideFullscreen(); web.loadUrl("about:blank"); finishAndRemoveTask();
                }).setOnDismissListener(dialog -> immersive()).show();
    }
    private void importConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT_CONFIG);
        } catch (Exception error) { toast("无法打开文件选择器：" + error.getMessage()); }
    }
    private void exportConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE, "WebView电视配置.json");
            startActivityForResult(intent, EXPORT_CONFIG);
        } catch (Exception error) { toast("无法打开保存窗口：" + error.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT_CONFIG) {
            String text = configRepository.exportText();
            workers.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("无法写入文件");
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    handler.post(() -> { if (!destroyed) toast("配置已导出，可以手动编辑后重新导入"); });
                } catch (Exception error) { handler.post(() -> { if (!destroyed) toast("导出失败：" + error.getMessage()); }); }
            });
        } else if (request == IMPORT_CONFIG) {
            workers.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (input == null) throw new IOException("无法读取文件");
                    byte[] bytes = new byte[8192]; int length;
                    while ((length = input.read(bytes)) != -1) { if (output.size() + length > 8 * 1024 * 1024) throw new IOException("配置文件超过 8 MB"); output.write(bytes, 0, length); }
                    configRepository.importText(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString());
                    String importWarning = configRepository.lastWarning();
                    JSONObject saved = configRepository.snapshot();
                    if (applyMappings(saved)) configRepository.save(saved);
                    JSONObject updated = configRepository.snapshot();
                    handler.post(() -> { if (!destroyed) { adoptConfig(updated); settingsView.open(config, configRepository.file().getPath(), channelSummary(), epgSummary()); channelStatus = importWarning; toast("配置已导入" + importWarning); refreshRepositories(false, false); } });
                } catch (Exception error) { handler.post(() -> { if (!destroyed) toast("导入失败，已有配置保留：" + error.getMessage()); }); }
            });
        }
    }
    private EditText editorField(LinearLayout parent, String label, String value, boolean multiline) {
        TextView title = new TextView(this); title.setText(label); title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(16)); title.setTextColor(0xFFBACAE0); parent.addView(title);
        EditText edit = new EditText(this); edit.setText(value); edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(multiline ? 15 : 18)); edit.setTextColor(Color.WHITE); edit.setPadding(px(6), px(5), px(6), px(5));
        edit.setSingleLine(!multiline); edit.setSelectAllOnFocus(!multiline);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        parent.addView(edit, new LinearLayout.LayoutParams(-1, multiline ? px(120) : px(48)));
        return edit;
    }
    private void editChannels() {
        new AlertDialog.Builder(this).setTitle("编辑频道配置")
                .setItems(new String[]{"编辑当前频道", "编辑完整 JSON 配置"}, (dialog, which) -> { if (which == 0) editCurrentChannel(); else editJson(); }).show();
    }
    private void editCurrentChannel() {
        JSONObject original = channels.get(channelIndex);
        final String key = channelKey(original);
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(px(24), px(10), px(24), px(10));
        EditText name = editorField(fields, "频道名称", original.optString("name"), false);
        EditText group = editorField(fields, "频道分组", original.optString("group"), false);
        EditText epgId = editorField(fields, "EPG 频道 ID（无对应节目可留空）", original.optString("epgId"), false);
        JSONArray urls = original.optJSONArray("urls"); StringBuilder lines = new StringBuilder();
        for (int i = 0; i < urls.length(); i++) { if (i > 0) lines.append('\n'); lines.append(urlOf(urls, i)); }
        EditText sources = editorField(fields, "播放地址（每行一个）", lines.toString(), true);
        StringBuilder parameterLines = new StringBuilder();
        for (int i = 0; i < urls.length(); i++) { if (i > 0) parameterLines.append('\n'); JSONObject item = urls.optJSONObject(i); if (item != null) parameterLines.append(item.optString("extra")); }
        EditText parameters = editorField(fields, "播放参数（每行对应一个地址，可留空）", parameterLines.toString(), true);
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("编辑 " + original.optString("name")).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                JSONObject updated = configRepository.snapshot(); JSONArray list = updated.getJSONArray("channels");
                int index = -1; for (int i = 0; i < list.length(); i++) if (channelKey(list.getJSONObject(i)).equals(key)) { index = i; break; }
                if (index < 0) throw new IOException("该频道已变更，请重新打开编辑");
                JSONObject item = list.getJSONObject(index); JSONArray nextSources = new JSONArray();
                String[] extraLines = parameters.getText().toString().split("\\r?\\n", -1);
                int sourcePosition = 0;
                for (String line : sources.getText().toString().split("\\r?\\n")) {
                    String url = line.trim(); if (url.isEmpty()) continue;
                    Uri address = Uri.parse(url);
                    if ((!"http".equals(address.getScheme()) && !"https".equals(address.getScheme())) || address.getHost() == null)
                        throw new IOException("播放地址必须是完整 HTTP/HTTPS 网址");
                    JSONObject existing = null;
                    for (int i = 0; i < urls.length(); i++) if (urlOf(urls, i).equals(url)) { existing = urls.optJSONObject(i); break; }
                    JSONObject replacement = existing == null ? new JSONObject() : new JSONObject(existing.toString());
                    replacement.put("url", url).put("extra", sourcePosition < extraLines.length ? extraLines[sourcePosition] : "");
                    if (replacement.optString("extra").length() > 16384) throw new IOException("播放参数过长");
                    nextSources.put(replacement); sourcePosition++;
                }
                if (name.getText().toString().trim().isEmpty() || name.length() > 120 || group.getText().toString().trim().isEmpty() || group.length() > 80 || epgId.length() > 160 || nextSources.length() == 0 || nextSources.length() > 32) throw new IOException("请填写有效的名称、分组和播放地址（最多32个）");
                item.put("name", name.getText().toString().trim()).put("group", group.getText().toString().trim())
                        .put("epgId", epgId.getText().toString().trim()).put("urls", nextSources);
                persistEdited(updated.toString(), dialog);
            } catch (Exception error) { toast("无法保存：" + error.getMessage()); }
        }));
        dialog.show(); dialog.getWindow().setLayout(px(900), px(620));
    }
    private void editJson() {
        EditText editor = new EditText(this); editor.setText(configRepository.exportText()); editor.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        editor.setGravity(Gravity.TOP); editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(false); editor.setPadding(px(18), px(12), px(18), px(12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("完整 JSON · 支持增删频道")
                .setView(editor).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> persistEdited(editor.getText().toString(), dialog)));
        dialog.show(); dialog.getWindow().setLayout(px(1060), px(620));
    }
    private void persistEdited(String text, AlertDialog dialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        workers.execute(() -> {
            try {
                configRepository.importText(text);
                String editWarning = configRepository.lastWarning();
                JSONObject saved = configRepository.snapshot();
                handler.post(() -> { if (!destroyed) { dialog.dismiss(); adoptConfig(saved); settingsView.open(config, configRepository.file().getPath(), channelSummary(), epgSummary()); channelStatus = editWarning; toast("频道配置已保存" + editWarning); refreshRepositories(false, false); } });
            } catch (Exception error) {
                handler.post(() -> { if (!destroyed) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); toast("保存失败，原配置保留：" + error.getMessage()); } });
            }
        });
    }
    private void resetDefaults() {
        new AlertDialog.Builder(this).setTitle("恢复内置频道？").setMessage("会替换频道列表及设置。建议先导出当前配置。")
                .setNegativeButton("取消", null).setPositiveButton("恢复", (dialog, which) -> {
                    workers.execute(() -> {
                        try {
                            JSONObject defaults = new JSONObject().put("channels", configRepository.builtInChannels());
                            configRepository.save(defaults);
                            JSONObject saved = configRepository.snapshot();
                            if (applyMappings(saved)) configRepository.save(saved);
                            JSONObject updated = configRepository.snapshot();
                            handler.post(() -> { if (!destroyed) { adoptConfig(updated); settingsView.open(config, configRepository.file().getPath(), channelSummary(), epgSummary()); toast("已恢复内置频道"); refreshRepositories(false, false); } });
                        } catch (Exception error) { handler.post(() -> { if (!destroyed) toast("恢复失败：" + error.getMessage()); }); }
                    });
                }).show();
    }

    public class Bridge {
        @JavascriptInterface public String getWebPageAdapter() { return adapterJson; }
        @JavascriptInterface public boolean isFastSwitchEnabled() { return fastSwitch; }
        @JavascriptInterface public String getPlaybackToken() { return playbackToken + ":" + documentSequence; }
        @JavascriptInterface public String getPreconnectOrigins() {
            JSONArray origins = new JSONArray();
            // Read only UI-published snapshot, never mutate the native list from the JS bridge thread.
            JSONArray list = config.optJSONArray("channels");
            if (list == null || list.length() < 2) return origins.toString();
            for (int step : new int[]{1, -1}) {
                JSONObject ch = list.optJSONObject(Math.floorMod(channelIndex + step, list.length()));
                if (ch == null) continue;
                String url = urlOf(ch.optJSONArray("urls"), 0);
                Uri parsed = Uri.parse(url);
                String scheme = parsed.getScheme(), host = parsed.getHost();
                if (("http".equals(scheme) || "https".equals(scheme)) && host != null) {
                    String origin = scheme + "://" + host + (parsed.getPort() < 0 ? "" : ":" + parsed.getPort());
                    if (!origins.toString().contains(origin)) origins.put(origin);
                }
            }
            return origins.toString();
        }
        @JavascriptInterface public void reportPlaybackReady(String token, String evidence) {
            runOnUiThread(() -> {
                if (destroyed || !token.equals(getPlaybackToken()) || firstFrameReported || channels.isEmpty()) return;
                firstFrameReported = true;
                sourceFailover.progress(SystemClock.elapsedRealtime());
                if (rememberSource) prefs.edit().putString("source:" + channelKey(channels.get(channelIndex)),
                        urlOf(channels.get(channelIndex).optJSONArray("urls"), sourceIndex)).apply();
                android.util.Log.i(TAG, "switch-ready channel=" + (channelIndex + 1) + " source=" + (sourceIndex + 1)
                        + " ms=" + (SystemClock.elapsedRealtime() - switchStarted) + " evidence=" + evidence + " fast=" + fastSwitch);
            });
        }
        @JavascriptInterface public void reportPlaybackProgress(String token) {
            runOnUiThread(() -> {
                if (destroyed || !Objects.equals(token, getPlaybackToken())) return;
                sourceFailover.progress(SystemClock.elapsedRealtime());
            });
        }
        @JavascriptInterface public String getExtra() { return extra; }
        @JavascriptInterface public void setVideoSize(int width, int height) {
            if (width > 0 && height > 0) runOnUiThread(() -> { videoWidth = width; videoHeight = height; });
        }
        @JavascriptInterface public void reportVideoLoadingState(boolean loading) { reportVideoLoadingStateForToken(getPlaybackToken(), loading); }
        @JavascriptInterface public void reportVideoLoadingStateForToken(String token, boolean loading) {
            runOnUiThread(() -> {
                if (destroyed || !Objects.equals(token, getPlaybackToken())) return;
                if (loading == buffering && !(!loading && !videoReady)) return;
                buffering = loading;
                if (loading) showHud("缓冲中", true, 0);
                else {
                    videoReady = true;
                    handler.removeCallbacks(noVideoHint);
                    showHud("确定打开频道菜单", false, 2400);
                }
            });
        }
        @JavascriptInterface public void reportViewportFullscreen(int width, int height, int mediaWidth, int mediaHeight) { reportViewportFullscreenForToken(getPlaybackToken(), width, height, mediaWidth, mediaHeight); }
        @JavascriptInterface public void reportViewportFullscreenForToken(String token, int width, int height, int mediaWidth, int mediaHeight) {
            runOnUiThread(() -> {
                if (destroyed || !Objects.equals(token, getPlaybackToken())) return;
                viewportFullscreen = true;
                web.scrollTo(0, 0);
                web.invalidate();
                android.util.Log.i(TAG, "viewport fullscreen=" + width + "x" + height + " video=" + mediaWidth + "x" + mediaHeight);
                if (mediaWidth > 0 && mediaHeight > 0) {
                    videoWidth = mediaWidth;
                    videoHeight = mediaHeight;
                    // Metadata updates the ratio; actual playing/canplay clears the loading HUD.
                }
            });
        }
        @JavascriptInterface public void sendKeyEvent(int key, boolean down) {
            if (down && key == 34) runOnUiThread(() -> web.evaluateJavascript(
                    "if(window.wvt_enterViewportFullscreen) window.wvt_enterViewportFullscreen();", null));
        }
        @JavascriptInterface public void schemeEnterFullscreen() { sendKeyEvent(34, true); }
    }
}
