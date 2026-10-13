/* Faster adapter discovery plus first decoded-frame timing; no media preloading. */
(function () {
    'use strict';
    if (window.wvt_fastSwitchController) return;

    var bridge = window.main;
    var enabled = false;
    var token = '';
    try {
        if (bridge && typeof bridge.isFastSwitchEnabled === 'function') {
            var value = bridge.isFastSwitchEnabled();
            enabled = value === true || value === 'true';
        }
        token = String(window.__wvtPlaybackToken || '');
    } catch (ignored) { /* Old builds retain playback with measurement disabled. */ }

    var started = Date.now();
    var stopped = false;
    var reported = false;
    var candidate = null;
    var frameHandle = null;
    var frameVideo = null;
    var watchTimer = 0;
    var loopTimer = 0;
    var preconnected = false;
    var lastError = '';
    var watched = new WeakMap();
    var playAttempts = new WeakMap();
    var playPending = new WeakSet();
    var state = { enabled: enabled, token: token, ready: false, evidence: '' };
    window.wvt_fastSwitchController = state;

    function attached(video) {
        return !!video && video.tagName === 'VIDEO' && (window.wvt_isMediaAttached
            ? window.wvt_isMediaAttached(video) : document.documentElement.contains(video));
    }
    function playable(video, decodedFrame) {
        return attached(video) && !video.paused && !video.ended && !video.error &&
            video.readyState >= (decodedFrame ? 2 : 3) && video.videoWidth > 0 && video.videoHeight > 0;
    }
    function selected(video) { return !stopped && candidate === video && window.wvt_video === video && attached(video); }
    function cancelFrame() {
        if (frameVideo && frameHandle !== null && typeof frameVideo.cancelVideoFrameCallback === 'function') {
            try { frameVideo.cancelVideoFrameCallback(frameHandle); } catch (ignored) { }
        }
        frameHandle = null; frameVideo = null;
    }
    function logFailure(error) {
        var text = String(error && error.message || error);
        if (text === lastError) return;
        lastError = text;
        console.warn('[WVTFast] adapter step: ' + text);
    }

    function runStage(action) {
        // Original DOM getters log every unsuccessful poll. Keep actual click,
        // discovery and media events while silencing only repeated search noise.
        if (typeof wvt_log !== 'function') return action();
        var logger = wvt_log;
        wvt_log = function (text) {
            if (/not found|^find .*condition =|not enough data/i.test(String(text))) return;
            logger(text);
        };
        try { return action(); } finally { wvt_log = logger; }
    }
    function preconnect() {
        if (!enabled || preconnected || stopped || !document.head) return;
        preconnected = true;
        try {
            if (!bridge || typeof bridge.getPreconnectOrigins !== 'function') return;
            var origins = JSON.parse(String(bridge.getPreconnectOrigins() || '[]'));
            if (!Array.isArray(origins)) return;
            var accepted = [];
            for (var i = 0; i < origins.length && accepted.length < 2; i++) {
                if (typeof origins[i] !== 'string') continue;
                var url;
                try { url = new URL(origins[i]); } catch (ignored) { continue; }
                if ((url.protocol !== 'https:' && url.protocol !== 'http:') || url.username || url.password ||
                    url.origin === window.location.origin || accepted.indexOf(url.origin) >= 0) continue;
                accepted.push(url.origin);
                var existing = document.querySelectorAll('link[rel="preconnect"]');
                var exists = false;
                for (var j = 0; j < existing.length; j++) if (existing[j].href.replace(/\/$/, '') === url.origin) exists = true;
                if (exists) continue;
                var link = document.createElement('link');
                link.rel = 'preconnect'; link.href = url.origin; link.crossOrigin = 'anonymous';
                document.head.appendChild(link);
            }
        } catch (ignored) { /* A connection hint must never affect channel playback. */ }
    }

    function ready(video, evidence) {
        if (reported || !selected(video) || !playable(video, evidence === 'decoded-frame') || !token || !bridge ||
            typeof bridge.reportPlaybackReady !== 'function') return;
        try {
            bridge.reportPlaybackReady(token, evidence);
            reported = true; state.ready = true; state.evidence = evidence;
            cancelFrame();
            console.log('[WVTFast] playback-ready ' + evidence);
            preconnect();
        } catch (error) { logFailure(error); }
    }

    function fallback(video, info, playingEvent) {
        if (!selected(video) || reported || !playable(video)) return;
        var frames = 0;
        try {
            if (typeof video.getVideoPlaybackQuality === 'function') frames = video.getVideoPlaybackQuality().totalVideoFrames || 0;
            else frames = video.webkitDecodedFrameCount || 0;
        } catch (ignored) { }
        // 'play' and metadata alone are insufficient: use playing, decoded frame
        // counts, or a media clock that actually advanced since attachment.
        if (playingEvent || frames > 0 || Math.abs(video.currentTime - info.initialTime) > 0.03)
            ready(video, 'playing-fallback');
    }

    function arm(video, info) {
        if (!selected(video) || reported) return;
        if (!info.frameCallback) { fallback(video, info, false); return; }
        if (frameHandle !== null || video.paused || video.error) return;
        frameVideo = video;
        try {
            frameHandle = video.requestVideoFrameCallback(function (now, metadata) {
                frameHandle = null; frameVideo = null;
                if (!selected(video) || reported) return;
                // rVFC runs when an actual decoded frame is submitted for presentation.
                if (metadata && metadata.presentedFrames > 0 && playable(video, true)) ready(video, 'decoded-frame');
                else arm(video, info);
            });
        } catch (ignored) {
            frameHandle = null; frameVideo = null; info.frameCallback = false;
            fallback(video, info, false);
        }
    }

    function observe() {
        if (stopped || reported) return;
        var video = window.wvt_video;
        if (!attached(video)) {
            if (candidate) { cancelFrame(); candidate = null; }
            return;
        }
        if (candidate !== video) { cancelFrame(); candidate = video; }
        var info = watched.get(video);
        if (!info) {
            info = { frameCallback: typeof video.requestVideoFrameCallback === 'function', initialTime: Number(video.currentTime) || 0 };
            watched.set(video, info);
            video.addEventListener('playing', function () {
                if (info.frameCallback) arm(video, info); else fallback(video, info, true);
            }, false);
            ['loadeddata', 'canplay', 'timeupdate'].forEach(function (event) {
                video.addEventListener(event, function () { arm(video, info); }, false);
            });
            video.addEventListener('emptied', function () {
                info.initialTime = Number(video.currentTime) || 0;
                if (selected(video)) cancelFrame();
            }, false);
        }
        arm(video, info);
    }

    function watch() {
        if (stopped || reported) return;
        observe();
        watchTimer = window.setTimeout(watch, Date.now() - started < 10000 ? 250 : 1500);
    }

    function resumeOnce(video) {
        if (!attached(video) || !video.paused || video.error || playPending.has(video)) return;
        var now = Date.now();
        if (now - (playAttempts.get(video) || 0) < 800) return;
        playAttempts.set(video, now);
        // At most one play() attempt and one adapter-authorized button click per
        // cycle. Avoid racing multiple unresolved play promises at 150 ms ticks.
        try {
            var promise = video.play();
            if (promise && typeof promise.then === 'function') {
                playPending.add(video);
                promise.then(function () { playPending.delete(video); observe(); }, function () { playPending.delete(video); });
            }
            if (video.paused && typeof wvt_needPlayButton === 'function' && wvt_needPlayButton()) {
                var button = runStage(wvt_getPlayButton);
                if (button) button.click();
            }
        } catch (error) { logFailure(error); }
    }

    function adapterCycle() {
        if (!document.body || !window.wvt_adapter) return;
        ++window.wvt_counter;
        if (typeof removeInvalidVideo === 'function' && removeInvalidVideo()) return;

        // Respect the first pre-click's DOM transition before looking for step 2.
        if (typeof wvt_needPreClick === 'function' && wvt_needPreClick() && !window.wvt_preClickFinished) {
            runStage(performPreClickIfNeeded);
            return;
        }
        if (typeof wvt_needPreClick2 === 'function' && wvt_needPreClick2() && !window.wvt_preClickFinished2) {
            // Existing adapters define step 2 only after step 1. Do not bypass it.
            if (!window.wvt_preClickFinished) return;
            runStage(performPreClick2IfNeeded);
            if (!window.wvt_preClickFinished2) return;
            // Step 2 and video discovery can share this cycle, eliminating a
            // redundant fixed 500 ms wait while retaining both prerequisites.
        }
        if (typeof findVideoAndSetupIfNeeded === 'function') runStage(findVideoAndSetupIfNeeded);
        resumeOnce(window.wvt_video);
        observe();
        if (typeof autoFullscreenIfNeeded === 'function') autoFullscreenIfNeeded();
    }

    function optimizedLoop() {
        if (stopped) return;
        try { adapterCycle(); } catch (error) { logFailure(error); }
        var video = window.wvt_video;
        var stable = playable(video);
        var interval = !stable && Date.now() - started < 10000 ? 150 : 1500;
        loopTimer = window.setTimeout(optimizedLoop, interval);
    }

    if (enabled && typeof wvt_loop === 'function') {
        // main.js lives in the surrounding injection IIFE. Its scheduled callback
        // resolves this lexical binding, so window alone would not replace it.
        wvt_loop = optimizedLoop;
        window.wvt_loop = optimizedLoop;
    }
    window.addEventListener('pagehide', function () {
        stopped = true;
        cancelFrame();
        if (watchTimer) window.clearTimeout(watchTimer);
        if (loopTimer) window.clearTimeout(loopTimer);
    }, { once: true });
    watch();
})();
