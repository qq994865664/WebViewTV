/* A click is a request, not proof that Guangdong's asynchronously bound player started. */
(function () {
    'use strict';
    if (location.hostname !== 'm.gdtv.cn' || window.wvt_gdtvStartup) return;
    var state = window.wvt_gdtvStartup = { clicks: 0, confirmed: false, hasSource: false, phase: 'waiting-button' };
    var lastClick = -Infinity;
    window.wvt_confirmGdtvStart = function () {
        var player = document.querySelector('.video-player');
        var video = player && player.querySelector('video');
        var button = player && player.querySelector('.playIco');
        var source = video && video.querySelector('source');
        state.hasSource = !!(video && (video.currentSrc || video.getAttribute('src') || (source && source.getAttribute('src'))));
        var hidden = !button || getComputedStyle(button).display === 'none' || getComputedStyle(button).visibility === 'hidden';
        var accepted = player && player.__vue__ && player.__vue__.isPlaying === true;
        var playing = video && !video.paused && !video.error && video.readyState >= 2 && video.videoWidth > 0 && video.currentTime > 0;
        if (playing || (state.hasSource && (hidden || accepted))) {
            state.confirmed = true;
            state.phase = 'source-assigned';
            window.wvt_preClickFinished = true;
            return true;
        }
        if (state.clicks >= 3) { state.phase = 'button-not-confirmed'; return false; }
        if (Date.now() - lastClick < 700) return false;
        // Keep the adapter's source-ready predicate: never click an empty poster or invent a stream URL.
        var eligible = typeof wvt_getPreClickElement === 'function' ? wvt_getPreClickElement() : null;
        if (!eligible) return false;
        lastClick = Date.now();
        state.clicks++;
        state.phase = 'confirming-click';
        eligible.click();
        return false; // Vue may bind <source> on the next task. Confirm on a later cycle.
    };
})();
