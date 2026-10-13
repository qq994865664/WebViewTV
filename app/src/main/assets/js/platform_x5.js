function wvt_reportVideoSize(video) {
    window.main.setVideoSize(video.videoWidth, video.videoHeight);
}

function wvt_sendKeyEvent(key, down) {
    window.main.sendKeyEvent(key, down);
}

function wvt_reportVideoLoadingState(isLoading) {
    window.main.reportVideoLoadingState(isLoading);
}

function wvt_setupVideoStyle(video) {
    video.style['object-fit'] = 'fill';
}

function wvt_init() {
    var json = window.main.getWebPageAdapter();
    window.wvt_adapter = JSON.parse(json);
    var extra = window.main.getExtra();
    window.wvt_extra = extra;

    wvt_main();
}