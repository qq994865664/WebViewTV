/* Hoolo's legacy player repeatedly reloads native HLS after Android MediaPlayer I/O errors. */
(function () {
    'use strict';
    if (location.hostname !== 'tv.hoolo.tv' || window.wvt_hlsRecovery || !window.Hls || !Hls.isSupported()) return;
    var timer = 0, engine = null, firstSource = '', firstSeen = 0, stopped = false;
    window.wvt_hlsRecovery = { attempted: false };
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
                    engine.on(Hls.Events.MANIFEST_PARSED, function () { replacement.play().catch(function () {}); });
                    engine.on(Hls.Events.ERROR, function (event, data) {
                        if (data.fatal && engine) { engine.destroy(); engine = null; }
                    });
                    engine.attachMedia(replacement);
                    engine.loadSource(source);
                    if (window.wvt_viewportController) window.wvt_viewportController.refresh();
                    console.log('[WVTHls] native HLS recovery started');
                } catch (error) { if (engine) { engine.destroy(); engine = null; } }
                return; // Native source timeout owns failure; never start an endless recovery loop.
            }
        }
        timer = setTimeout(check, playing ? 1500 : 250);
    }
    window.addEventListener('pagehide', function () {
        stopped = true;
        clearTimeout(timer);
        if (engine) { engine.destroy(); engine = null; }
    }, { once: true });
    check();
})();
