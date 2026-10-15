function wvt_setupVideo(video) {
	if (video.wvt_setup) {
		return;
	}
	if (document.fullscreenElement) {
		document.exitFullscreen();
	}
    
    video.autoplay = true;
    video.removeAttribute('controls');
	wvt_setupVideoListeners(video);
	wvt_setupVideoStyle(video)

	video.wvt_setup = true;
	wvt_log("Video setup successfully");
}

function wvt_tryEnterFullscreen(video) {
    if (video.readyState >= HTMLMediaElement.HAVE_METADATA) {
        window.focus();
        document.body.focus();
        wvt_sendKey(34)
        wvt_reportVideoSize(video);
        setTimeout(() => { wvt_ensureVideoVolume(video) }, 1000);
    } else {
        wvt_log("Can't enter fullscreen due to not enough data.");
    }
}

function wvt_setupNonVideo(element) {
	window.focus();
    document.body.focus();
    window.main.schemeEnterFullscreen();
}

function wvt_playVideo() {
    if (window.wvt_video != null && window.wvt_video.paused) {
        window.wvt_video.play();
    }
    if (window.wvt_video == null || window.wvt_video.paused) {
        if (wvt_needPlayButton()) {
            var element = wvt_getPlayButton();
            if (element) {
            	element.click();
            	wvt_log("Play button clicked.");
            } else {
                wvt_log("Play button not found.");
            }
        }
    }
}

function removeInvalidVideo(){
	if (window.wvt_video && !document.documentElement.contains(window.wvt_video)) {
		wvt_log("Video element is removed from DOM, clear reference.");
		if (document.fullscreenElement) {
			document.exitFullscreen();
		}
		window.wvt_video = null;
		return true;
	}
	return false;
}

function playVideoIfPaused() {
	if (window.wvt_video && window.wvt_video.paused && window.wvt_video.error == null) {
		wvt_playVideo();
		return true;
	}
	return false;
}

function performPreClickIfNeeded() {
	if (wvt_needPreClick() && !window.wvt_preClickFinished) {
		var element = wvt_getPreClickElement();
		if (element == null) {
			wvt_log("pre click element not found");
			return false;
		}
		wvt_log("pre click tag = " + element.tagName + ".");
		element.click();
		window.wvt_preClickFinished = true;
		return true;
	}
}

function performPreClick2IfNeeded() {
	if (window.wvt_preClickFinished && wvt_needPreClick2() && !window.wvt_preClickFinished2) {
		var element = wvt_getPreClickElement2();
		if (element == null) {
			wvt_log("pre click element 2 not found");
			return false;
		}
		wvt_log("pre click 2 tag = " + element.tagName + ".");
		element.click();
		window.wvt_preClickFinished2 = true;
		return true;
	}
}

function findVideoAndSetupIfNeeded() {
	if (window.wvt_video != null ) {
		return false;
	}
	var element = wvt_getVideoElement();
	if (element) {
		wvt_log("fullscreen tag = " + element.tagName + ".");
		window.wvt_video = element;
		if (element.tagName == 'VIDEO') {
			wvt_setupVideo(element);
		} else {
			wvt_setupNonVideo(element);
		}
		return true;
	}
	return false;
}

function autoFullscreenIfNeeded() {
	if (window.wvt_video && !window.wvt_video.paused && !document.fullscreenElement) {
		wvt_log("Video is playing but not in fullscreen, try to enter fullscreen.");
		wvt_tryEnterFullscreen(window.wvt_video);
		return true;
	}
	return false;
}

function wvt_loop() {
	setTimeout(() => { wvt_loop() }, document.fullscreenElement ? 5000 : 500);
	var counter = ++window.wvt_counter;

	const fns = [
		removeInvalidVideo,
		playVideoIfPaused,
		performPreClickIfNeeded,
		performPreClick2IfNeeded,
		findVideoAndSetupIfNeeded,
		autoFullscreenIfNeeded
	];

	for (const fn of fns) {
		if (fn()) { break; }
	}
}

function wvt_makeVideoFullscreen() {
	if (document.fullscreenElement != null) {
		wvt_error("already in fullscreen.");
		return;
	}
	if (window.wvt_video && document.documentElement.contains(window.wvt_video)) {
	    var element = null;
	    try {
	        element = wvt_needEnterFullscreenButton() ? wvt_getEnterFullscreenButton() : null;
	    } catch (e) {
	        wvt_error("use requestFullscreen(), element [" + enterFullscreenButton + "] not found.");
	    }
	    if (element != null) {
	        element.click();
	    } else {
	        window.wvt_video.requestFullscreen();
	    }
	} else {
		wvt_error("wvt_video is null.");
	}
}

function wvt_main() {
	document.addEventListener('keydown', function(e) {
		wvt_log('Key down: ' + e.key);
		if (e.key == 'f') { wvt_makeVideoFullscreen(); }
	}, true)
	window.wvt_counter = 0;
	setTimeout(() => { wvt_loop() }, 1000);
}

if (!window.wvt_javascriptInjected) {
	wvt_log("javascript injected successfully.");
	wvt_init();
	window.wvt_javascriptInjected = true;
} else {
	wvt_error("javascript already injected.");
}
