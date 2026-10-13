function wvt_log(s) { console.log("[WebViewTV] " + s); }

function wvt_error(s) { console.error("[WebViewTV] " + s); }

function wvt_sendKey(key) {
    wvt_sendKeyEvent(key, true);
    wvt_sendKeyEvent(key, false);
}

function wvt_ensureVideoVolume(video) {
    video.muted = false;
    video.defaultMuted = false;
    video.volume = 1;
}

function wvt_setupVideoListeners(video) {
    video.addEventListener('play', function () {
        wvt_log("Video state: PLAY.");
        wvt_tryEnterFullscreen(video);
        wvt_reportVideoLoadingState(false);
    });
    video.addEventListener('loadstart', function () {
        wvt_log("Video state: LOADSTART.");
    });
    video.addEventListener('waiting', function () {
        wvt_log("Video state: WAITING.");
        wvt_reportVideoLoadingState(true);
    });
    video.addEventListener('pause', function () {
        wvt_log("Video state: PAUSE.");
    });
    video.addEventListener('error', function (e) {
        wvt_log("Video state: ERROR, code = " + (video.error ? video.error.code : "player-event") + ".");
    });
    video.addEventListener('canplay', function (e) {
        wvt_log("Video state: CANPLAY.");
        wvt_tryEnterFullscreen(video);
    });
    video.addEventListener('resize', function (e) {
        wvt_log("Video state: RESIZE.");
        wvt_reportVideoSize(video);
    });
    //    video.addEventListener('canplaythrough', function(e) {
    //    	wvt_log("Video state: CANPLAYTHROUGH.");
    //    });
    //    video.addEventListener('durationchange', function(e) {
    //    	console.log("Video state: DURATIONCHANGE.");
    //    });
}

function wvt_getVideoElement() {
    var nodes = document.all;
    var extra = window.wvt_extra;
    for (var i = 0; i < nodes.length; i++){
    	var e = nodes[i];
    	if (eval(window.wvt_adapter.videoElementCondition)) {
    	    return e;
    	}
    }
    return null;
}

function wvt_getEnterFullscreenButton() {
    var nodes = document.all;
    var extra = window.wvt_extra;
    for (var i = 0; i < nodes.length; i++){
    	var e = nodes[i];
    	if (eval(window.wvt_adapter.enterFullscreenButtonCondition)) {
    	    return e;
    	}
    }
    return null;
}

function wvt_getPlayButton() {
    wvt_log("find play button, condition = " + window.wvt_adapter.playButtonCondition + ".");
    var nodes = document.all;
    var extra = window.wvt_extra;
    for (var i = 0; i < nodes.length; i++){
    	var e = nodes[i];
    	if (eval(window.wvt_adapter.playButtonCondition)) {
    	    return e;
    	}
    }
    return null;
}

function wvt_getPreClickElement() {
    wvt_log("find pre click element, condition = " + window.wvt_adapter.preClickElementCondition + ".");
    var nodes = document.all;
    var extra = window.wvt_extra;
    for (var i = 0; i < nodes.length; i++){
    	var e = nodes[i];
    	if (eval(window.wvt_adapter.preClickElementCondition)) {
    	    return e;
    	}
    }
    return null;
}

function wvt_getPreClickElement2() {
    wvt_log("find pre click element2, condition = " + window.wvt_adapter.preClickElementCondition2 + ".");
    var nodes = document.all;
    var extra = window.wvt_extra;
    for (var i = 0; i < nodes.length; i++){
    	var e = nodes[i];
    	if (eval(window.wvt_adapter.preClickElementCondition2)) {
    	    return e;
    	}
    }
    return null;
}

function wvt_needPreClick() {
    var extra = window.wvt_extra;
    return eval(window.wvt_adapter.needPreClick);
}

function wvt_needPreClick2() {
    var extra = window.wvt_extra;
    return eval(window.wvt_adapter.needPreClick2);
}

function wvt_needEnterFullscreenButton() {
    var extra = window.wvt_extra;
    return eval(window.wvt_adapter.needEnterFullscreenButton);
}

function wvt_needPlayButton() {
    var extra = window.wvt_extra;
    return eval(window.wvt_adapter.needPlayButton);
}