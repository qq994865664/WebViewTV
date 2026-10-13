/* Native TV playback uses the WebView viewport; browser Fullscreen needs a user gesture. */
(function () {
    'use strict';
    if (window.wvt_viewportController) {
        window.wvt_enterViewportFullscreen();
        return;
    }

    var target = null;
    var changes = [];
    var bound = new WeakSet();
    var stabilized = new WeakSet();
    var pendingStabilization = null;
    var stabilizationFrame = 0;
    var queued = 0;
    var lastReport = '';
    var lastDiscovery = 0;
    var reportedTarget = null;
    var lastFailureSignature = '';
    var lastFailureLog = 0;
    var lastLoading = null;
    var viewportDocuments = new WeakSet();
    var controlsSelector = '.vjs-control-bar,.vjs-big-play-button,.plyr__controls,' +
        '.dplayer-controller,.jw-controlbar,.jw-display-controls,' +
        '.xgplayer-controls,.xgplayer-start';
    window.wvt_isViewportFullscreen = false;

    function attached(element) {
        if (!element || element.nodeType !== 1) return false;
        var owner = element.ownerDocument || document;
        if (!owner.documentElement.contains(element)) return false;
        if (owner === document) return true;
        try { return attached(owner.defaultView.frameElement); } catch (ignored) { return false; }
    }

    function isVisible(element) {
        if (!attached(element)) return false;
        var rect = element.getBoundingClientRect();
        // Some responsive wrappers collapse to zero height while the stream is already decoding.
        // A real playing video may be promoted out of that layout; hidden/inactive players remain excluded.
        var decoding = element.tagName === 'VIDEO' && element.readyState >= 2 && !element.paused &&
            !element.error && element.videoWidth > 0 && element.videoHeight > 0;
        if (!decoding && (rect.width < 60 || rect.height < 35)) return false;
        for (var node = element; node; node = node.parentElement) {
            var style = window.getComputedStyle(node);
            if (style.display === 'none' || style.visibility === 'hidden' ||
                Number(style.opacity) === 0) return false;
        }
        return true;
    }

    function isAdvertisement(element) {
        var label = (element.id || '') + ' ' + (element.className || '');
        return /(?:^|[-_\s])(advert(?:isement)?|preroll|pre-roll|ad-player|ad-video)(?:$|[-_\s])/i.test(label);
    }

    function findTarget() {
        // Do not lock a poster/title player fullscreen before the channel-selection steps.
        if (typeof wvt_needPreClick === 'function' && wvt_needPreClick() && !window.wvt_preClickFinished) return null;
        if (typeof wvt_needPreClick2 === 'function' && wvt_needPreClick2() && !window.wvt_preClickFinished2) return null;
        // The adapter identifies the site's real player, including container/iframe players.
        var adapted = window.wvt_video;
        var adaptedMedia = adapted ? mediaElement(adapted) : null;
        if (adaptedMedia && isVisible(adaptedMedia) && !adaptedMedia.error && !adaptedMedia.paused && adaptedMedia.readyState >= 2) return adaptedMedia;
        // Lazy players can appear after onPageFinished; limit scans during DOM activity.
        if (Date.now() - lastDiscovery < 700) return target && attached(target) ? target : null;
        lastDiscovery = Date.now();
        var videos = [];
        function collect(doc, depth) {
            videos = videos.concat(Array.prototype.slice.call(doc.querySelectorAll('video')));
            if (depth >= 2) return;
            var frames = doc.querySelectorAll('iframe');
            for (var f = 0; f < frames.length; f++) {
                try { if (frames[f].contentDocument && isVisible(frames[f])) collect(frames[f].contentDocument, depth + 1); } catch (ignored) { }
            }
        }
        collect(document, 0);
        var best = null;
        var bestScore = -1;
        for (var i = 0; i < videos.length; i++) {
            var video = videos[i];
            if (!isVisible(video) || isAdvertisement(video)) continue;
            var rect = video.getBoundingClientRect();
            var score = Math.min(rect.width * rect.height, 1000000);
            if (!video.paused && !video.ended) score += 4000000;
            if (video.readyState >= 1 && video.videoWidth >= 160) score += 2000000;
            if (video.error) score -= 8000000;
            if (score > bestScore) { best = video; bestScore = score; }
        }
        if (best) return best;
        return adapted && adapted !== document.body && adapted !== document.documentElement && isVisible(adapted) ? adapted : null;
    }

    function change(element, property, value) {
        var style = element.style;
        if (style.getPropertyValue(property) === value && style.getPropertyPriority(property) === 'important') return;
        var record = null;
        for (var i = 0; i < changes.length; i++) {
            if (changes[i].element === element && changes[i].property === property) {
                record = changes[i];
                break;
            }
        }
        if (!record) {
            record = { element: element, property: property,
                value: style.getPropertyValue(property), priority: style.getPropertyPriority(property) };
            changes.push(record);
        }
        record.applied = value;
        style.setProperty(property, value, 'important');
    }

    function restore() {
        for (var i = changes.length - 1; i >= 0; i--) {
            var record = changes[i];
            // Respect a site update that replaced our value after it was applied.
            if (record.element.style.getPropertyValue(record.property) === record.applied &&
                record.element.style.getPropertyPriority(record.property) === 'important') {
                if (record.value) record.element.style.setProperty(record.property, record.value, record.priority);
                else record.element.style.removeProperty(record.property);
            }
        }
        changes.length = 0;
        window.wvt_isViewportFullscreen = false;
    }

    function clearContainingBlocks(element) {
        for (var parent = element.parentElement; parent; parent = parent.parentElement) {
            var computed = window.getComputedStyle(parent);
            // A transformed/filtered/clipped ancestor prevents fixed video from covering the viewport.
            var rules = { 'zoom': '1', 'transform': 'none', 'perspective': 'none', 'filter': 'none',
                'backdrop-filter': 'none', 'contain': 'none', 'content-visibility': 'visible',
                'will-change': 'auto', 'overflow': 'visible', 'clip': 'auto', 'clip-path': 'none' };
            for (var key in rules) {
                var value = computed.getPropertyValue(key);
                if (value && value !== rules[key] && value !== 'normal') change(parent, key, rules[key]);
            }
            // Masking does not affect getBoundingClientRect, but can still crop video paint.
            var mask = computed.getPropertyValue('mask-image');
            var webkitMask = computed.getPropertyValue('-webkit-mask-image');
            if ((mask && mask !== 'none') || (webkitMask && webkitMask !== 'none')) {
                change(parent, 'mask', 'none');
                change(parent, '-webkit-mask', 'none');
            }
            // Raise the whole ancestry above page navigation without moving the player DOM.
            if (parent !== document.body && parent !== document.documentElement) {
                if (computed.position === 'static') change(parent, 'position', 'relative');
                change(parent, 'z-index', '2147483646');
            }
        }
        var owner = element.ownerDocument || document;
        change(owner.documentElement, 'background-color', 'black');
        if (owner.body) change(owner.body, 'background-color', 'black');
    }

    function coverViewport(element) {
        var owner = element.ownerDocument || document;
        if (!viewportDocuments.has(owner)) {
            var meta = owner.querySelector('meta[name="viewport"]');
            if (!meta) { meta = owner.createElement('meta'); meta.name = 'viewport'; (owner.head || owner.documentElement).appendChild(meta); }
            meta.setAttribute('content', 'width=device-width, initial-scale=1');
            viewportDocuments.add(owner);
        }
        if (owner !== document) {
            try { if (owner.defaultView.frameElement) coverViewport(owner.defaultView.frameElement); } catch (ignored) { }
        }
        clearContainingBlocks(element);
        [owner.documentElement, owner.body].forEach(function (root) {
            if (!root) return;
            change(root, 'min-width', '0px');
            change(root, 'max-width', '100vw');
            change(root, 'width', '100vw');
            change(root, 'overflow', 'hidden');
            change(root, 'margin', '0px');
            change(root, 'padding', '0px');
        });
        var rules = { 'position': 'fixed', 'left': '0px', 'top': '0px', 'right': 'auto', 'bottom': 'auto',
            'width': '100vw', 'height': '100vh', 'min-width': '0px', 'min-height': '0px',
            'max-width': 'none', 'max-height': 'none', 'margin': '0px', 'padding': '0px',
            'border': '0px', 'border-radius': '0px', 'box-sizing': 'border-box',
            'z-index': '2147483647', 'object-fit': 'contain', 'object-position': 'center center',
            'background-color': 'black', 'visibility': 'visible', 'opacity': '1',
            'display': 'block', 'zoom': '1', 'transform': 'none', 'transition': 'none', 'animation': 'none',
            'clip': 'auto', 'clip-path': 'none', 'mask': 'none', '-webkit-mask': 'none', 'overflow': 'visible' };
        for (var property in rules) change(element, property, rules[property]);
        if (element.tagName === 'VIDEO') {
            element.controls = false;
            element.removeAttribute('controls');
            // Only hide recognized transport controls inside this player's nearby wrapper.
            // Error/login dialogs are left visible; the app must not mask a website failure.
            if (element.readyState >= 1 && element.parentElement) {
                var controls = element.parentElement.querySelectorAll(controlsSelector);
                for (var i = 0; i < controls.length; i++) {
                    if (!controls[i].contains(element)) change(controls[i], 'display', 'none');
                }
            }
        }
    }

    function mediaElement(element) {
        if (element.tagName === 'VIDEO') return element;
        if (element.tagName === 'IFRAME') {
            try { return element.contentDocument && element.contentDocument.querySelector('video'); } catch (ignored) { return null; }
        }
        return element.querySelector('video');
    }

    function hasPageOffset() {
        var scrolling = document.scrollingElement || document.documentElement;
        return Math.abs(window.pageXOffset || 0) > 0.5 || Math.abs(window.pageYOffset || 0) > 0.5 ||
            Math.abs(scrolling.scrollLeft || 0) > 0.5 || Math.abs(scrolling.scrollTop || 0) > 0.5;
    }

    function stabilizeFirstFrame(element) {
        var video = mediaElement(element);
        // A pre-metadata/empty video is not ready; retry when it actually reaches canplay.
        if (!video || video.paused || video.readyState < 3 || !video.videoWidth || !video.videoHeight ||
            stabilized.has(video) || pendingStabilization === video) return;
        pendingStabilization = video;
        window.scrollTo(0, 0);
        stabilizationFrame = window.requestAnimationFrame(function () {
            stabilizationFrame = 0;
            if (pendingStabilization !== video) return;
            pendingStabilization = null;
            if (target !== element || !attached(element) || video.readyState < 3 ||
                !video.videoWidth || !video.videoHeight) return;
            if (hasPageOffset()) window.scrollTo(0, 0);
            // Reconcile paint after the first live frame and WebView's initial overview layout.
            coverViewport(element);
            report(element);
            stabilized.add(video);
        });
    }

    function clearLoadingIfReady(video, resumed) {
        if (!video || video.paused || video.readyState < 3 || video.error || lastLoading === false || (lastLoading === true && !resumed)) return;
        lastLoading = false;
        if (window.main && typeof window.main.reportVideoLoadingState === 'function') {
            window.main.reportVideoLoadingState(false);
        }
    }

    function bindVideo(element) {
        var video = mediaElement(element);
        if (!video || bound.has(video)) return;
        bound.add(video);
        ['loadedmetadata', 'loadeddata', 'canplay', 'playing', 'resize', 'emptied'].forEach(function (event) {
            video.addEventListener(event, function () {
                if (event === 'canplay' || event === 'playing') clearLoadingIfReady(video, true);
                if (event === 'emptied') {
                    lastLoading = null;
                    stabilized.delete(video);
                    if (pendingStabilization === video) pendingStabilization = null;
                }
                schedule();
            }, false);
        });
        // base.js reports waiting=true. Remember that transition so a later
        // canplay can clear the HUD even when playback resumes without a new play event.
        video.addEventListener('waiting', function () { lastLoading = true; }, false);
    }

    function report(element) {
        var rect = element.getBoundingClientRect();
        // 100vw/100vh use the layout viewport in CSS pixels. Sites such as CCTV
        // zoom their root and overflow horizontally, so innerWidth/innerHeight
        // can describe the overflow area rather than the visible layout viewport.
        // This check is shared by video, adapter containers and iframe targets.
        var owner = element.ownerDocument || document;
        var layoutViewport = owner.documentElement;
        var visualViewport = owner.defaultView.visualViewport;
        var viewportWidth = Math.round(layoutViewport.clientWidth ||
            (visualViewport && visualViewport.width) || window.innerWidth);
        var viewportHeight = Math.round(layoutViewport.clientHeight ||
            (visualViewport && visualViewport.height) || window.innerHeight);
        var fits = Math.abs(rect.left) < 3 && Math.abs(rect.top) < 3 &&
            Math.abs(rect.width - viewportWidth) < 3 && Math.abs(rect.height - viewportHeight) < 3;
        window.wvt_isViewportFullscreen = fits;
        if (!fits) {
            var layout = document.documentElement;
            var visual = window.visualViewport;
            var failure = JSON.stringify({
                rect: [rect.left, rect.top, rect.width, rect.height].map(function (value) { return Math.round(value * 100) / 100; }),
                viewport: [viewportWidth, viewportHeight],
                inner: [window.innerWidth, window.innerHeight],
                layout: [layout.clientWidth, layout.clientHeight],
                visual: visual ? [visual.width, visual.height, visual.scale] : null,
                zoom: [window.getComputedStyle(layout).zoom, document.body ? window.getComputedStyle(document.body).zoom : null,
                    window.getComputedStyle(element).zoom],
                position: window.getComputedStyle(element).position
            });
            if (failure !== lastFailureSignature && Date.now() - lastFailureLog >= 5000) {
                lastFailureSignature = failure;
                lastFailureLog = Date.now();
                console.log('[WVTViewport] fit=false ' + failure);
            }
            return;
        }
        lastFailureSignature = '';
        var video = mediaElement(element);
        var videoWidth = video ? video.videoWidth || 0 : 0;
        var videoHeight = video ? video.videoHeight || 0 : 0;
        var signature = [viewportWidth, viewportHeight, videoWidth, videoHeight].join(':');
        if (signature !== lastReport || reportedTarget !== element) {
            lastReport = signature;
            reportedTarget = element;
            if (window.main && typeof window.main.reportViewportFullscreen === 'function') {
                window.main.reportViewportFullscreen(viewportWidth, viewportHeight, videoWidth, videoHeight);
            }
        }
    }

    function enter() {
        var next = findTarget();
        if (!next) {
            if (target && !attached(target)) { restore(); target = null; }
            return false;
        }
        if (next !== target) {
            restore();
            target = next;
            pendingStabilization = null;
            lastReport = '';
            lastLoading = null;
            bindVideo(target);
        }
        // Discovery, playback health and first-frame reporting must agree on the media.
        var media = mediaElement(target);
        if (media && window.wvt_video !== media) {
            window.wvt_video = media;
            if (typeof wvt_setupVideo === 'function') wvt_setupVideo(media);
        }
        coverViewport(target);
        bindVideo(target);
        clearLoadingIfReady(mediaElement(target));
        stabilizeFirstFrame(target);
        report(target);
        return window.wvt_isViewportFullscreen;
    }

    function schedule() {
        if (queued) return;
        queued = window.setTimeout(function () { queued = 0; enter(); }, 120);
    }

    window.wvt_enterViewportFullscreen = enter;
    window.wvt_isMediaAttached = attached;
    window.wvt_viewportController = { refresh: schedule };

    // This file is appended in the same injection scope as main.js. Replace its
    // repeated requestFullscreen/key injection with the viewport check above.
    if (typeof autoFullscreenIfNeeded === 'function') {
        autoFullscreenIfNeeded = function () {
            if (!window.wvt_isViewportFullscreen || (target && !attached(target))) enter();
            return false;
        };
    }
    if (typeof wvt_setupVideoStyle === 'function') {
        wvt_setupVideoStyle = function (video) { video.style.setProperty('object-fit', 'contain', 'important'); };
    }
    if (typeof removeInvalidVideo === 'function') {
        removeInvalidVideo = function () {
            if (window.wvt_video && !attached(window.wvt_video)) { window.wvt_video = null; return true; }
            return false;
        };
    }
    if (typeof wvt_tryEnterFullscreen === 'function') {
        wvt_tryEnterFullscreen = function (video) {
            enter();
            if (video && video.tagName === 'VIDEO' && video.readyState >= 1) {
                if (typeof wvt_reportVideoSize === 'function') wvt_reportVideoSize(video);
                if (typeof wvt_ensureVideoVolume === 'function') wvt_ensureVideoVolume(video);
            }
        };
    }

    window.addEventListener('scroll', function () {
        if (window.wvt_isViewportFullscreen && target && attached(target) && hasPageOffset()) {
            window.scrollTo(0, 0);
        }
        schedule();
    }, { passive: true });
    window.addEventListener('resize', schedule, false);
    window.addEventListener('orientationchange', schedule, false);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', schedule, false);
    var observer = new MutationObserver(schedule);
    observer.observe(document.documentElement, { childList: true, subtree: true });
    // Bound the maintenance work; style writes occur only if the site changed them.
    var watchdog = window.setInterval(enter, 1500);
    window.addEventListener('pagehide', function () {
        observer.disconnect();
        window.clearInterval(watchdog);
        if (queued) window.clearTimeout(queued);
        if (stabilizationFrame) window.cancelAnimationFrame(stabilizationFrame);
        pendingStabilization = null;
    }, { once: true });
    enter();
})();




