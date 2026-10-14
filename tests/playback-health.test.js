const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const code = fs.readFileSync(require('path').join(__dirname, '../app/src/main/assets/js/playback_health.js'), 'utf8');
let checks = 0;
function equal(actual, expected, label) { assert.deepEqual(actual, expected, label); checks++; }
function check(value, label) { assert.ok(value, label); checks++; }
function eventTarget() {
    const listeners = new Map();
    return {
        listeners,
        addEventListener(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
        removeEventListener(name, fn) { if (listeners.has(name)) listeners.get(name).delete(fn); },
        emit(name, target = this) {
            for (const fn of [...(listeners.get(name) || [])]) fn({ type: name, target, currentTarget: this });
        },
        listenerCount() { return [...listeners.values()].reduce((sum, list) => sum + list.size, 0); }
    };
}
function media(overrides = {}) {
    return Object.assign(eventTarget(), { tagName: 'VIDEO', currentTime: 0, paused: false, ended: false,
        error: null, readyState: 4, videoWidth: 1920, videoHeight: 1080, attached: true }, overrides);
}
function fixture(video = media(), frames = false) {
    let now = 0, next = 1;
    const tasks = new Map(), frameTasks = new Map(), cancelledFrames = [], reports = [], stageTokens = [], stages = [], buffers = [], ready = [];
    const document = Object.assign(eventTarget(), { documentElement: { contains: v => v.attached } });
    const window = Object.assign(eventTarget(), {
        wvt_video: video, __wvtPlaybackToken: '7:2',
        main: {
            // Java bridge methods resolve the exact argument count, unlike ordinary JS functions.
            reportPlaybackStageForToken(...args) { assert.equal(args.length, 1, 'stage bridge arity'); stageTokens.push(args[0]); },
            reportPlaybackProgress(...args) { assert.equal(args.length, 1, 'progress bridge arity'); reports.push(args[0]); },
            reportMediaStage(...args) { assert.equal(args.length, 2, 'media bridge arity'); stages.push(args); },
            reportBufferProgress(...args) { assert.equal(args.length, 2, 'buffer bridge arity'); buffers.push(args); },
            reportPlaybackReady(...args) { assert.equal(args.length, 2, 'ready bridge arity'); ready.push(args); }
        },
        setTimeout: (fn, delay) => { const id = next++; tasks.set(id, { fn, due: now + delay, delay }); return id; },
        clearTimeout: id => tasks.delete(id)
    });
    const observers = [];
    class MutationObserver {
        constructor(fn) { this.fn = fn; this.disconnected = false; observers.push(this); }
        observe() { }
        disconnect() { this.disconnected = true; }
        notify() { if (!this.disconnected) this.fn([]); }
    }
    function addFrames(candidate) {
        candidate.requestVideoFrameCallback = fn => { const id = next++; frameTasks.set(id, { fn, video: candidate }); return id; };
        candidate.cancelVideoFrameCallback = id => { cancelledFrames.push(id); frameTasks.delete(id); };
    }
    if (frames) addFrames(video);
    vm.runInNewContext(code, { window, document, MutationObserver, Date: { now: () => now } });
    return {
        window, document, tasks, frameTasks, cancelledFrames, observers, reports, stageTokens, stages, buffers, ready, addFrames,
        advance(ms) {
            const until = now + ms;
            let turns = 0;
            while (true) {
                const task = [...tasks].sort((a, b) => a[1].due - b[1].due)[0];
                if (!task || task[1].due > until) break;
                assert.ok(++turns < 1000, 'timer must stay bounded');
                now = task[1].due; tasks.delete(task[0]); task[1].fn();
            }
            now = until;
        },
        frame(metadata) {
            const task = [...frameTasks][0];
            assert.ok(task, 'frame callback is armed');
            frameTasks.delete(task[0]); task[1].fn(now, metadata);
        }
    };
}

const video = media(), f = fixture(video);
equal(f.stageTokens, ['7:2'], 'initial media discovery reaches strict one-argument stage bridge');
equal(f.reports.length, 0, 'metadata alone is not playback');
f.advance(250); equal(f.reports.length, 0, 'stationary clock is not playback');
video.currentTime = 1; video.emit('timeupdate');
equal(f.reports, ['7:2'], 'media event reports without waiting for watchdog');
video.paused = true; video.currentTime = 2; video.emit('timeupdate'); equal(f.reports.length, 1, 'paused video ignored');
video.paused = false; video.readyState = 1; video.currentTime = 3; video.emit('timeupdate'); equal(f.reports.length, 1, 'buffering is not playback');
video.readyState = 4; video.error = {}; video.currentTime = 4; video.emit('error'); equal(f.reports.length, 1, 'error is not playback');
video.error = null; video.ended = true; video.currentTime = 5; video.emit('timeupdate'); equal(f.reports.length, 1, 'ended video ignored');
video.ended = false; video.currentTime = 6; video.emit('timeupdate'); equal(f.reports.length, 2, 'recovery reports');
video.currentTime = 0; video.emit('timeupdate'); equal(f.reports.length, 2, 'backward clock ignored');
video.attached = false; video.currentTime = 1; f.observers[0].notify(); f.advance(120);
equal(f.reports.length, 2, 'detached media ignored');
equal(video.listenerCount(), 0, 'detachment removes media listeners');
video.attached = true; f.observers[0].notify(); f.advance(120); equal(f.reports.length, 2, 'reattachment resets clock baseline');
video.currentTime = 2; video.emit('timeupdate'); equal(f.reports.length, 3, 'reattached clock movement reports');

const replacement = media({ currentTime: 30 });
f.window.wvt_video = replacement;
f.document.emit('loadedmetadata', replacement);
equal(f.reports.length, 3, 'new video starts fresh baseline immediately on event');
equal(video.listenerCount(), 0, 'replacement unbinds old video');
video.currentTime = 20; video.emit('timeupdate'); equal(f.reports.length, 3, 'old-video events cannot report current playback');
replacement.currentTime = 31; replacement.emit('timeupdate'); equal(f.reports.length, 4, 'replacement progress reports current token');
replacement.readyState = 1; replacement.emit('loadedmetadata'); equal(f.stages.at(-1), ['7:2', 2], 'metadata event advances stage immediately');
replacement.readyState = 2; replacement.emit('loadeddata'); equal(f.stages.at(-1), ['7:2', 3], 'decoded-data event advances stage immediately');
replacement.buffered = { length: 1, start: () => 30, end: () => 32 }; replacement.emit('progress');
equal(f.stages.at(-1), ['7:2', 4], 'buffer covering position advances stage');
equal(f.buffers.at(-1), ['7:2', 1000], 'buffer reports actual playable ahead duration');
const beforeBuffer = f.buffers.length, beforeStage = f.stages.length;
replacement.emit('progress'); replacement.emit('canplay'); f.advance(1500);
equal(f.buffers.length, beforeBuffer, 'repeated buffer readings are not loading progress');
equal(f.stages.length, beforeStage, 'repeated stage events are not new evidence');
replacement.buffered.end = () => 32.01; replacement.emit('progress'); equal(f.buffers.length, beforeBuffer, 'tiny buffer jitter is ignored');
replacement.buffered.end = () => 33; replacement.emit('progress'); equal(f.buffers.at(-1), ['7:2', 2000], 'actual buffer growth reports promptly');
replacement.currentTime = 30; replacement.emit('progress'); equal(f.buffers.length, beforeBuffer + 1, 'seeking backwards does not fake buffer growth');
replacement.buffered = { length: 1, start: () => 40, end: () => 45 }; replacement.emit('progress');
equal(f.stages.at(-1), ['7:2', 3], 'unrelated future range is not usable startup buffer');
f.window.emit('pagehide'); equal(f.tasks.size, 0, 'pagehide clears timers');
equal(replacement.listenerCount(), 0, 'pagehide removes video listeners');
equal(f.document.listenerCount(), 0, 'pagehide removes capture listeners');
check(f.observers[0].disconnected, 'pagehide disconnects DOM observer');

const waiting = fixture(null);
equal([...waiting.tasks.values()][0].delay, 250, 'startup discovery samples promptly');
waiting.advance(10000);
equal([...waiting.tasks.values()][0].delay, 1500, 'high-frequency discovery stops after ten seconds');
const late = media(); waiting.window.wvt_video = late;
waiting.observers[0].notify(); waiting.observers[0].notify();
equal(waiting.tasks.size, 2, 'DOM bursts coalesce to one discovery task');
waiting.advance(120); check(late.listenerCount() > 0, 'DOM change discovers late video within 120 ms');
waiting.window.__wvtPlaybackToken = '8:0'; late.currentTime = 1; late.emit('timeupdate');
equal(waiting.reports.length, 0, 'changed page token suppresses stale reports');
equal(waiting.tasks.size, 0, 'changed token cleans up watchdog');

const frameVideo = media(), frames = fixture(frameVideo, true);
frames.frame({ presentedFrames: 0, mediaTime: 0 }); equal(frames.ready.length, 0, 'empty frame metadata is not readiness');
frames.frame({ presentedFrames: 1, mediaTime: 0 });
equal(frames.ready, [['7:2', 'health-frame']], 'presented first frame proves readiness even at time zero');
equal(frames.frameTasks.size, 0, 'first-frame callback stops after readiness');
frames.advance(250); equal([...frames.tasks.values()][0].delay, 1500, 'actual playback switches discovery to low frequency');

const invalid = fixture(media(), true);
invalid.window.wvt_video.videoHeight = 0;
invalid.frame({ presentedFrames: 1, mediaTime: 1 }); equal(invalid.ready.length, 0, 'zero-sized video cannot reveal playback');
invalid.window.wvt_video.videoHeight = 1080; invalid.window.wvt_video.emit('loadeddata');
invalid.window.wvt_video.paused = true;
invalid.frame({ presentedFrames: 1, mediaTime: 1 }); equal(invalid.ready.length, 0, 'paused frame cannot reveal playback');
invalid.window.wvt_video.paused = false; invalid.window.wvt_video.emit('playing');
invalid.frame({ presentedFrames: 0, mediaTime: 2 }); equal(invalid.ready.length, 1, 'advancing callback mediaTime supplies valid frame evidence');

const race = fixture(media(), true);
const stale = [...race.frameTasks.values()][0].fn, newer = media(); race.addFrames(newer);
race.window.wvt_video = newer; race.document.emit('loadeddata', newer);
check(race.cancelledFrames.length > 0, 'replacement cancels old frame callback');
const newHandle = [...race.frameTasks.keys()][0];
stale(0, { presentedFrames: 10, mediaTime: 10 });
equal(race.ready.length, 0, 'late cancelled callback cannot report readiness');
check(race.frameTasks.has(newHandle), 'stale callback cannot erase replacement frame request');
race.frame({ presentedFrames: 1, mediaTime: 0 }); equal(race.ready.length, 1, 'replacement frame remains observable');
newer.emit('emptied'); check(race.frameTasks.size > 0, 'same-element reload resets first-frame evidence');
race.window.emit('pagehide'); equal(race.frameTasks.size, 0, 'pagehide cancels remaining frame request');

const reloadVideo = media(), reload = fixture(reloadVideo, true);
reload.frame({ presentedFrames: 1, mediaTime: 0 });
equal(reload.reports.length, 1, 'first decoded frame reports playback progress');
reloadVideo.currentTime = 2; reloadVideo.emit('timeupdate');
reloadVideo.readyState = 0; reloadVideo.currentTime = 0;
reloadVideo.emit('emptied'); reloadVideo.emit('loadstart');
reloadVideo.readyState = 2; reloadVideo.emit('loadeddata');
equal(reload.reports.length, 2, 'reload metadata alone does not clear buffering');
reload.frame({ presentedFrames: 1, mediaTime: 0 });
equal(reload.ready.length, 2, 'same-video reload emits new decoded-frame readiness');
equal(reload.reports.length, 3, 'recovered first frame immediately reports progress before clock advances');
reloadVideo.currentTime = .2; reloadVideo.emit('timeupdate');
equal(reload.reports.length, 4, 'same-video reload continues normal playback progress');
console.log(`PASS ${checks} playback health assertions`);
