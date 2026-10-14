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
