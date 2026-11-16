/* Bounded recovery for the site's cleared startup timer; use its own line selector/player. */
(function () {
    'use strict';
    if (location.hostname !== 'iptv345.com' || window.wvt_iptv345Startup) return;
    var query = new URL(location.href).searchParams;
    if (query.get('act') !== 'play' || query.get('tid') !== 'itv' || !/^(4|5)$/.test(query.get('id') || '')) return;
    var token = window.__wvtPlaybackToken, timer = 0, stopped = false, discovery = 0;
    var tried = {}, line = -1, since = 0, media = null, lastTime = null;
    var state = window.wvt_iptv345Startup = { phase: 'waiting-player', line: 0, attempts: 0, stop: stop };
    function stop() {
        stopped = true; clearTimeout(timer);
        window.removeEventListener('pagehide', stop, false);
    }
    function watch() {
        if (stopped || window.__wvtPlaybackToken !== token) { stop(); return; }
        var select = document.querySelector('select#playURL'), video = document.querySelector('video#vstPlayer');
        if (!select || !video || typeof window.onLineChange !== 'function' || select.selectedIndex < 0) {
            if (++discovery >= 40) { state.phase = 'player-not-ready'; stop(); return; }
        } else {
            if (line !== select.selectedIndex || media !== video) {
                line = select.selectedIndex; media = video; lastTime = null; since = Date.now();
                tried[line] = true; state.line = line + 1; state.phase = 'waiting-playback';
            }
            var time = Number(video.currentTime);
            if (lastTime !== null && isFinite(time) && time > lastTime + 0.03 && !video.paused && !video.ended &&
                    !video.error && video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
                state.phase = 'playing'; stop(); return;
            }
            lastTime = isFinite(time) ? time : null;
            if (Date.now() - since >= 5000) {
                var next = -1, count = Math.min(select.options.length, 8);
                for (var step = 1; step < count; step++) {
                    var candidate = (line + step) % count;
                    if (!tried[candidate] && !select.options[candidate].disabled && select.options[candidate].value) { next = candidate; break; }
                }
                if (next < 0) { state.phase = 'lines-exhausted'; stop(); return; }
                select.selectedIndex = next; tried[next] = true; state.attempts++;
                line = next; state.line = next + 1; since = Date.now(); lastTime = null;
                try { window.onLineChange(select); }
                catch (error) {
                    state.phase = 'player-error'; stop(); return;
                }
            }
        }
        timer = setTimeout(watch, 250);
    }
    window.addEventListener('pagehide', stop, false);
    watch();
})();
