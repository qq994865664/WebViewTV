/* Media-clock progress only: metadata/canplay do not prove continuous playback. */
(function () {
    'use strict';
    if (window.wvt_playbackHealth) return;
    window.wvt_playbackHealth = true;
    var token = window.__wvtPlaybackToken;
    var video = null, lastTime = null, timer = 0, stopped = false;
    function sample() {
        if (stopped) return;
        var current = window.wvt_video;
        if (current !== video) {
            video = current; lastTime = null;
            if (video && video.tagName === 'VIDEO') {
                try { window.main.reportPlaybackStageForToken(token); } catch (ignored) { }
            }
        }
        if (video && (window.wvt_isMediaAttached ? window.wvt_isMediaAttached(video) : document.documentElement.contains(video))) {
            var evidence = 1;
            if (video.readyState >= 1 && video.videoWidth > 0) evidence = 2;
            if (video.readyState >= 2 && video.videoWidth > 0 && !video.error) evidence = 3;
            try {
                var buffered = video.buffered;
                for (var i=0; buffered && i<buffered.length; i++) {
                    if (buffered.start(i)<=video.currentTime && buffered.end(i)-video.currentTime>=0.5 && evidence>=3) evidence=4;
                }
                window.main.reportMediaStage(token, evidence);
            } catch (ignored) { }
            var time = Number(video.currentTime);
            if (isFinite(time) && lastTime !== null && time > lastTime + 0.03 &&
                    !video.paused && !video.ended && !video.error && video.readyState >= 2 &&
                    video.videoWidth > 0 && video.videoHeight > 0) {
                try { window.main.reportPlaybackProgress(token); } catch (ignored) { }
            }
            lastTime = isFinite(time) ? time : null;
        } else lastTime = null;
        timer = window.setTimeout(sample, 1000);
    }
    window.addEventListener('pagehide', function () {
        stopped = true;
        window.clearTimeout(timer);
    }, { once: true });
    sample();
})();
