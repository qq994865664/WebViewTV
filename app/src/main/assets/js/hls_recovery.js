/* One startup recovery for site-assigned native HLS; never replace a working player. */
(function () {
    'use strict';
    var gdtv = location.hostname === 'm.gdtv.cn';
    if ((!gdtv && location.hostname !== 'tv.hoolo.tv') || window.wvt_hlsRecovery || !window.Hls || !Hls.isSupported()) return;
    var timer = 0, reportTimer = 0, engine = null, firstSource = '', firstSeen = 0, stopped = false;
    var token = window.__wvtPlaybackToken;
    var state = window.wvt_hlsRecovery = { attempted: false, stop: stop, stage: 'native', bytes: 0, fragments: 0 };
    function host(url) { try { return new URL(url).hostname; } catch (ignore) { return ''; } }
    function code(value) { return typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(value) ? value : ''; }
    function active(current) { return !stopped && engine === current && token === window.__wvtPlaybackToken; }
    function describeError(data) {
        var response = data.response || {}, status = response.code;
        return { type: code(data.type), details: code(data.details), fatal: data.fatal === true,
            httpStatus: typeof status === 'number' && status >= 100 && status <= 599 ? status : null,
            host: host(response.url || data.url || (data.frag && data.frag.url) || (data.context && data.context.url) || firstSource) };
    }
    function reportFailure(error) {
        var issue = 'hls-failed';
        if (error.httpStatus === 401 || error.httpStatus === 403) issue = 'hls-access-denied';
        else if (error.httpStatus === 404 || error.httpStatus === 410) issue = 'hls-not-found';
        else if (error.details === 'manifestIncompatibleCodecsError') issue = 'hls-unsupported';
        else if (error.type === 'networkError') issue = 'hls-network';
        else if (error.type === 'mediaError') issue = 'hls-media';
        try { if (window.main && typeof window.main.reportSiteIssue === 'function') window.main.reportSiteIssue(token, issue); } catch (ignore) {}
    }
    function stop() {
        stopped = true;
        clearTimeout(timer);
        destroyEngine();
    }
    function destroyEngine() {
        clearTimeout(reportTimer);
        reportTimer = 0;
        var current = engine;
        engine = null; // Reject any late event, including callbacks during destroy().
        if (current) current.destroy();
    }
    function observeTransport(current) {
        var token = window.__wvtPlaybackToken, seen = new WeakMap();
        var received = 0, reported = 0, lastReport = 0;
        function active() { return !stopped && engine === current && typeof token === 'string' && token.length > 0 && token === window.__wvtPlaybackToken; }
        function report() {
            reportTimer = 0;
            if (!active() || received <= reported) return;
            var bridge = window.main;
            if (!bridge || typeof bridge.reportMediaBytes !== 'function') return;
            try {
                // Network activity only: manifest bytes do not prove media readiness or a first frame.
                bridge.reportMediaBytes(token, received);
                reported = received;
                lastReport = Date.now();
            } catch (ignore) {}
        }
        function receivedStats(stats) {
            if (!active() || !stats || stats.aborted || typeof stats.loaded !== 'number' || !isFinite(stats.loaded)) return;
            var loaded = Math.floor(stats.loaded), previous = seen.get(stats) || 0;
            if (loaded <= previous || loaded <= 0) return;
            seen.set(stats, loaded);
            received += loaded - previous;
            state.bytes = received;
            if (!reported || Date.now() - lastReport >= 500) {
                clearTimeout(reportTimer);
                report();
            } else if (!reportTimer) reportTimer = setTimeout(report, 500 - (Date.now() - lastReport));
        }
        // HLS 1.7.3 reports a request's LoadStats object on manifest/fragment completion.
        // LOADED and BUFFERED can share that object: count each byte once, without retaining URLs.
        current.on(Hls.Events.MANIFEST_LOADED, function (event, data) {
            if (!active()) return;
            state.stage = 'manifest-loaded';
            receivedStats(data && data.stats);
        });
        current.on(Hls.Events.FRAG_LOADED, function (event, data) {
            if (!active()) return;
            state.stage = 'fragment-loaded';
            state.fragments++;
            var fragment = data && (data.part || data.frag);
            receivedStats(fragment && fragment.stats);
        });
        current.on(Hls.Events.FRAG_BUFFERED, function (event, data) {
            if (!active()) return;
            state.stage = 'buffered';
            receivedStats(data && data.stats);
        });
        current.on(Hls.Events.DESTROYING, function () {
            clearTimeout(reportTimer);
            reportTimer = 0;
            if (engine === current) engine = null;
        });
    }
    function check() {
        if (stopped || token !== window.__wvtPlaybackToken) return;
        if (gdtv && window.wvt_gdtvStartup && !window.wvt_gdtvStartup.confirmed) {
            timer = setTimeout(check, 250);
            return;
        }
        var video = document.querySelector(gdtv ? '.video-player video' : '#m2o_liveplayer video');
        var sourceElement = video && video.querySelector && video.querySelector('source');
        var source = video && (video.currentSrc || video.src || (sourceElement && sourceElement.src));
        if (source && /^https?:\/\//i.test(source) && /\.m3u8(?:\?|$)/i.test(source)) {
            if (source !== firstSource) { firstSource = source; firstSeen = Date.now(); }
            // Keep a working website player. Recovery is once per page, using its own public URL.
            var playing = !video.paused && !video.error && video.readyState >= 2 && video.videoWidth > 0 && video.currentTime > 0;
            if (playing) return; // Startup recovery only; later stalls use the native source watchdog.
            var failed = video.error && video.error.code >= 2 && video.error.code <= 4;
            // Guangdong's mobile native HLS can take longer than three seconds to start.
            // Only a concrete media error authorizes replacement there; the native watchdog owns slow startup.
            if (failed || (!gdtv && !video.error && video.readyState < 2 && Date.now() - firstSeen >= 3000)) {
                state.attempted = true;
                state.stage = 'starting';
                state.native = { host: host(source), error: video.error ? video.error.code : 0,
                    ready: video.readyState, network: video.networkState, paused: video.paused,
                    width: video.videoWidth, height: video.videoHeight, elapsedMs: Date.now() - firstSeen };
                try {
                    if (video.player && typeof video.player.dispose === 'function') video.player.dispose();
                    else { video.pause(); video.removeAttribute('src'); video.load(); video.remove(); }
                    var replacement = document.createElement('video');
                    replacement.autoplay = true;
                    replacement.playsInline = true;
                    replacement.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;background:black';
                    document.body.appendChild(replacement);
                    window.wvt_video = replacement;
                    if (typeof wvt_setupVideo === 'function') wvt_setupVideo(replacement);
                    engine = new Hls({ enableWorker: false, maxBufferLength: 15, backBufferLength: 10 });
                    observeTransport(engine);
                    var current = engine;
                    engine.on(Hls.Events.MANIFEST_PARSED, function () {
                        if (!active(current)) return;
                        state.stage = 'manifest-parsed';
                        replacement.play().catch(function (error) {
                            if (active(current)) state.playError = code(error && error.name);
                        });
                    });
                    engine.on(Hls.Events.ERROR, function (event, data) {
                        if (!active(current) || !data) return;
                        state.lastError = describeError(data);
                        if (data.fatal) {
                            // Preserve the cause before destroy() detaches MediaSource and clears video.error/src.
                            state.failed = true;
                            state.failedStage = state.stage;
                            state.stage = 'failed';
                            reportFailure(state.lastError);
                            destroyEngine();
                        }
                    });
                    engine.attachMedia(replacement);
                    engine.loadSource(source);
                    if (window.wvt_viewportController) window.wvt_viewportController.refresh();
                    console.log('[WVTHls] native HLS recovery started');
                } catch (error) {
                    state.failed = true;
                    state.failedStage = state.stage;
                    state.stage = 'failed';
                    state.lastError = { type: 'initializationError', details: code(error && error.name), fatal: true, httpStatus: null, host: host(source) };
                    reportFailure(state.lastError);
                    destroyEngine();
                }
                return; // Native source timeout owns failure; never start an endless recovery loop.
            }
        }
        timer = setTimeout(check, playing ? 1500 : 250);
    }
    window.addEventListener('pagehide', stop, { once: true });
    check();
})();
