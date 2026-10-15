/* Report a visible site restriction, without changing authentication or its player. */
(function () {
    'use strict';
    var cctv = location.hostname === 'tv.cctv.com';
    if ((!cctv && !/^(www\.)?btime\.(com|cn)$/.test(location.hostname)) || window.wvt_siteStatus) return;
    window.wvt_siteStatus = true;
    var timer, stopped = false, matches = 0, attempts = 0, token = window.__wvtPlaybackToken;
    function check() {
        if (stopped || token !== window.__wvtPlaybackToken || ++attempts > 30) return;
        var videos = document.querySelectorAll('video'), playing = false;
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            if (!v.paused && !v.error && v.readyState >= 2 && v.videoWidth > 0 && v.currentTime > 0) playing = true;
        }
        if (playing) return;
        var text = document.body ? document.body.innerText : '';
        // Player notices can be split across lines or inline spans.
        text = text.replace(/\s+/g, '');
        var restricted = cctv ? /由于播出安排变更[，,]?暂不支持播放该时段内容/.test(text)
                : /登录后即可观看精彩节目/.test(text);
        matches = restricted ? matches + 1 : 0;
        if (matches >= 2 && window.main && typeof window.main.reportSiteIssue === 'function') {
            window.main.reportSiteIssue(token, cctv ? 'schedule-unavailable' : 'login-required');
            return;
        }
        timer = setTimeout(check, 1000);
    }
    window.addEventListener('pagehide', function () { stopped = true; clearTimeout(timer); }, { once: true });
    check();
})();
