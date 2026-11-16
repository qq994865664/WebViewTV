/* Open the site's own cross-origin player so the normal WebView adapter can control its video. */
(function () {
    'use strict';
    if (location.hostname !== 'zuiai.tv' || !/^\/i\/1453\/?$/.test(location.pathname) || window.wvt_zuiaiStartup) return;
    var token = window.__wvtPlaybackToken, timer = 0, stopped = false, attempts = 0;
    var state = window.wvt_zuiaiStartup = { phase: 'waiting-player' };
    function stop() {
        stopped = true;
        clearTimeout(timer);
        window.removeEventListener('pagehide', stop, false);
    }
    function findPlayer() {
        if (stopped || window.__wvtPlaybackToken !== token) { stop(); return; }
        var frame = document.querySelector('iframe#vlive');
        var target = null;
        try { if (frame) target = new URL(frame.getAttribute('src'), location.href); } catch (ignored) { }
        if (target && target.protocol === 'https:' && target.hostname === 'so.zuiai.tv' && !target.port &&
                !target.username && !target.password && !target.hash &&
                (target.pathname === '/m3u8/live/' || target.pathname === '/m3u8/') && target.searchParams.get('ch')) {
            state.phase = 'opening-player';
            stop();
            // Preserve the website-provided query; do not extract or hardcode a live stream.
            location.replace(target.href);
            return;
        }
        if (++attempts >= 40) { state.phase = 'player-not-found'; stop(); return; }
        timer = setTimeout(findPlayer, 250);
    }
    window.addEventListener('pagehide', stop, false);
    findPlayer();
})();
