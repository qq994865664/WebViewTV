/* Events advance startup evidence; only decoded frames/media-clock movement prove playback. */
(function () {
    'use strict';
    if (window.wvt_playbackHealth) return;
    window.wvt_playbackHealth = true;
    var token = window.__wvtPlaybackToken;
    var video = null, lastTime = null, lastBufferAhead = 0, lastBufferEnd = -1, lastStage = 0;
    var timer = 0, queued = 0, stopped = false, observer = null;
    var frameHandle = null, frameVideo = null, lastFrameTime = null, frameReady = false, generation = 0;
    var started = Date.now(), seenPlayback = false;
    var events = ['loadedmetadata', 'loadeddata', 'progress', 'canplay', 'playing',
        'timeupdate', 'waiting', 'stalled', 'error', 'emptied', 'loadstart'];

    function attached(media) {
        return !!media && media.tagName === 'VIDEO' && (window.wvt_isMediaAttached
            ? window.wvt_isMediaAttached(media) : document.documentElement.contains(media));
    }
    function playable(media) {
        return attached(media) && !media.paused && !media.ended && !media.error &&
            media.readyState >= 2 && media.videoWidth > 0 && media.videoHeight > 0;
    }
    function active() {
        if (!stopped && window.__wvtPlaybackToken !== token) stop();
        return !stopped;
    }
    function report(method, value) {
        try {
            if (window.main && typeof window.main[method] === 'function') {
                // Android JavascriptInterface resolves methods by arity. An extra
                // undefined argument is not interchangeable with a one-argument call.
                if (arguments.length === 1) window.main[method](token);
                else window.main[method](token, value);
            }
        } catch (ignored) { }
    }
    function cancelFrame() {
        if (frameVideo && frameHandle !== null && typeof frameVideo.cancelVideoFrameCallback === 'function') {
            try { frameVideo.cancelVideoFrameCallback(frameHandle); } catch (ignored) { }
        }
        frameHandle = null; frameVideo = null;
    }
    function resetEvidence() {
        generation++;
        cancelFrame();
        lastTime = null; lastBufferAhead = 0; lastBufferEnd = -1; lastStage = 0;
        lastFrameTime = video && isFinite(Number(video.currentTime)) ? Number(video.currentTime) : null;
        frameReady = false;
    }
    function unbind() {
        if (video && typeof video.removeEventListener === 'function') {
            for (var i = 0; i < events.length; i++) video.removeEventListener(events[i], onMediaEvent, false);
        }
        resetEvidence();
        video = null;
    }
    function select() {
        var current = window.wvt_video;
        if (!attached(current)) current = null;
        if (current === video) return;
        unbind();
        video = current;
        if (!video) return;
        resetEvidence();
        if (typeof video.addEventListener === 'function') {
            for (var i = 0; i < events.length; i++) video.addEventListener(events[i], onMediaEvent, false);
        }
        report('reportPlaybackStageForToken');
    }
    function armFrame() {
        if (frameReady || frameHandle !== null || !playable(video) ||
                typeof video.requestVideoFrameCallback !== 'function') return;
        var candidate = video, frameGeneration = generation;
        frameVideo = candidate;
        try {
            frameHandle = candidate.requestVideoFrameCallback(function (now, metadata) {
                if (frameGeneration !== generation || frameVideo !== candidate) return;
                frameHandle = null; frameVideo = null;
                if (!active() || video !== candidate || window.wvt_video !== candidate || !playable(candidate)) return;
                var mediaTime = metadata && Number(metadata.mediaTime);
                var advanced = isFinite(mediaTime) && lastFrameTime !== null && mediaTime > lastFrameTime + 0.03;
                // A positive presented-frame count proves even the first frame at mediaTime 0.
                if (metadata && (metadata.presentedFrames > 0 || advanced)) {
                    frameReady = true; seenPlayback = true;
                    report('reportPlaybackReady', 'health-frame');
                    // Ready records the initial startup only. A new decoded frame after
                    // an emptied/srcObject reload must also clear an existing buffer wait.
                    report('reportPlaybackProgress');
                } else {
                    if (isFinite(mediaTime)) lastFrameTime = mediaTime;
                    armFrame();
                }
            });
        } catch (ignored) { frameHandle = null; frameVideo = null; }
    }
    function sample() {
        if (!active()) return;
        select();
        if (!video) return;
        var evidence = 1, ahead = 0, bufferEnd = -1;
        if (video.readyState >= 1 && video.videoWidth > 0 && video.videoHeight > 0) evidence = 2;
        if (video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0 && !video.error) evidence = 3;
        var time = Number(video.currentTime);
        try {
            var buffered = video.buffered;
            for (var i = 0; isFinite(time) && buffered && i < buffered.length; i++) {
                if (buffered.start(i) <= time && buffered.end(i) >= time) {
                    ahead = Math.max(ahead, Math.round((buffered.end(i) - time) * 1000));
                    bufferEnd = Math.max(bufferEnd, buffered.end(i));
                }
            }
        } catch (ignored) { }
        if (ahead >= 500 && evidence >= 3) evidence = 4;
        if (evidence !== lastStage) {
            report('reportMediaStage', evidence);
            lastStage = evidence;
        }
        // Reading the same range again (or consuming it) is not new loading progress.
        if (!video.error && ahead > lastBufferAhead + 50 && bufferEnd > lastBufferEnd + 0.05)
            report('reportBufferProgress', ahead);
        lastBufferAhead = ahead; lastBufferEnd = Math.max(lastBufferEnd, bufferEnd);
        if (isFinite(time) && lastTime !== null && time > lastTime + 0.03 && playable(video)) {
            seenPlayback = true;
            report('reportPlaybackProgress');
        }
        lastTime = isFinite(time) ? time : null;
        armFrame();
    }
    function onMediaEvent(event) {
        if (!active()) return;
        // Direct handlers from replaced media cannot renew the current source's budget.
        if (event && event.currentTarget && event.currentTarget !== video) return;
        if (event && (event.type === 'emptied' || event.type === 'loadstart')) resetEvidence();
        sample();
    }
    function onDocumentEvent(event) {
        if (event && event.target === window.wvt_video && event.target !== video) sample();
    }
    function schedule() {
        if (!active() || queued) return;
        // Coalesce noisy DOM changes; never poll the DOM on every animation frame.
        queued = window.setTimeout(function () { queued = 0; sample(); }, 120);
    }
    function watch() {
        if (!active()) return;
        sample();
        timer = window.setTimeout(watch, !seenPlayback && Date.now() - started < 10000 ? 250 : 1500);
    }
    function stop() {
        if (stopped) return;
        stopped = true;
        unbind();
        window.clearTimeout(timer); window.clearTimeout(queued);
        timer = 0; queued = 0;
        if (observer) observer.disconnect();
        if (typeof document.removeEventListener === 'function') {
            for (var i = 0; i < events.length; i++) document.removeEventListener(events[i], onDocumentEvent, true);
        }
        window.removeEventListener('pagehide', stop, false);
    }
    if (typeof document.addEventListener === 'function') {
        // Capturing media events also discovers a newly selected main-document video immediately.
        for (var i = 0; i < events.length; i++) document.addEventListener(events[i], onDocumentEvent, true);
    }
    if (typeof MutationObserver === 'function') {
        observer = new MutationObserver(schedule);
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }
    window.addEventListener('pagehide', stop, false);
    watch();
})();
