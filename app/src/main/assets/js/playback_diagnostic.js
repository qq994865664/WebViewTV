/* Return only bounded technical state. Never copy cookies, page text or signed URLs. */
(function () {
    function host(url) { try { return new URL(url, location.href).hostname; } catch (ignore) { return ''; } }
    return {
        injected: window.wvt_javascriptInjected === true,
        preClick: window.wvt_preClickFinished === true,
        viewport: window.wvt_isViewportFullscreen === true,
        hlsRecovery: !!(window.wvt_hlsRecovery && window.wvt_hlsRecovery.attempted),
        hlsFailed: !!(window.wvt_hlsRecovery && window.wvt_hlsRecovery.failed),
        videos: Array.prototype.slice.call(document.querySelectorAll('video'), 0, 4).map(function (v) {
            return { host: host(v.currentSrc || v.src), ready: v.readyState, network: v.networkState,
                error: v.error ? v.error.code : 0, paused: v.paused, width: v.videoWidth, height: v.videoHeight,
                time: Math.round((v.currentTime || 0) * 10) / 10 };
        })
    };
})()
