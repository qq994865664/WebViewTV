/* Hoolo's legacy player repeatedly reloads native HLS after Android MediaPlayer I/O errors. */
(function () {
    'use strict';
    if (location.hostname !== 'tv.hoolo.tv' || window.wvt_hlsRecovery || !window.Hls || !Hls.isSupported()) return;
    var timer = 0, reportTimer = 0, engine = null, firstSource = '', firstSeen = 0, stopped = false;
    window.wvt_hlsRecovery = { attempted: false };
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
            if (!reported || Date.now() - lastReport >= 500) {
                clearTimeout(reportTimer);
                report();
            } else if (!reportTimer) reportTimer = setTimeout(report, 500 - (Date.now() - lastReport));
        }
        // HLS 1.7.3 reports a request's LoadStats object on manifest/fragment completion.
        // LOADED and BUFFERED can share that object: count each byte once, without retaining URLs.
        current.on(Hls.Events.MANIFEST_LOADED, function (event, data) { receivedStats(data && data.stats); });
        current.on(Hls.Events.FRAG_LOADED, function (event, data) {
            var fragment = data && (data.part || data.frag);
            receivedStats(fragment && fragment.stats);
        });
        current.on(Hls.Events.FRAG_BUFFERED, function (event, data) { receivedStats(data && data.stats); });
        current.on(Hls.Events.DESTROYING, function () {
            clearTimeout(reportTimer);
            reportTimer = 0;
            if (engine === current) engine = null;
        });
    }
    function check() {
        if (stopped) return;
        var video = document.querySelector('#m2o_liveplayer video');
        var source = video && (video.currentSrc || video.src);
        if (source && /^https?:\/\//i.test(source) && /\.m3u8(?:\?|$)/i.test(source)) {
            if (source !== firstSource) { firstSource = source; firstSeen = Date.now(); }
            // Keep a working website player. Recovery is once per page, using its own public URL.
            var playing = !video.paused && !video.error && video.readyState >= 2 && video.videoWidth > 0 && video.currentTime > 0;
            if (!playing && (video.error || Date.now() - firstSeen >= 3000)) {
                window.wvt_hlsRecovery.attempted = true;
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
                    engine.on(Hls.Events.MANIFEST_PARSED, function () { replacement.play().catch(function () {}); });
                    engine.on(Hls.Events.ERROR, function (event, data) {
                        if (data && data.fatal) destroyEngine();
                    });
                    engine.attachMedia(replacement);
                    engine.loadSource(source);
                    if (window.wvt_viewportController) window.wvt_viewportController.refresh();
                    console.log('[WVTHls] native HLS recovery started');
                } catch (error) { destroyEngine(); }
                return; // Native source timeout owns failure; never start an endless recovery loop.
            }
        }
        timer = setTimeout(check, playing ? 1500 : 250);
    }
    window.addEventListener('pagehide', function () {
        stopped = true;
        clearTimeout(timer);
        destroyEngine();
    }, { once: true });
    check();
})();
