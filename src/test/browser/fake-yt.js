// A fake of the YouTube IFrame API for the browser tests (see README.md). The stand-in server injects it at the start of <head>.
//
// It keeps the real iframe_api script from loading (the real script would need the network and would put a real player in the
// page) and gives the page a YT.Player that logs every call. A scenario controls it through window.__fake:
//   position          what getCurrentTime() answers (seconds)
//   emit(state)       makes the player report a state, as the real one does on its own
//   end()             the track ends (state ENDED)
//   holdState         null (default), 'UNSTARTED' or 'CUED': after loadVideoById the player stays in that state, without
//                     playing, until release() — the real player does that for a moment between two videos, and the
//                     script has to cope with it (the pause button, the lease reports)
//   release()         lets a held load go on (BUFFERING, then PLAYING)
//   advanceClock(ms)  moves Date.now() forward, so that a scenario need not wait for real seconds (the script measures
//                     "10 s after the restart" with Date.now())
//   loads, seeks      the video ids passed to loadVideoById, the positions passed to seekTo
//   calls             every call, in order: [name, argument]
//   blockApi          true: the IFrame API never loads (an ad blocker, Brave shields): the script's <script> tag is dropped, no
//                     YT.Player is ever made, and nothing is reported — as it is in the real page, where the failure is silent
//   start()           on a page opened by a scenario the API is not "loaded" until the scenario has configured the stand-in
//                     server (harness.js calls it) — otherwise the script would ask for its first track before the answers exist.
//                     The first lease report of the page waits for it too, so that its answer is the scenario's and not the default
(function () {
    const S = { UNSTARTED: -1, ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5 };
    const fake = {
        loads: [], seeks: [], calls: [], position: 0, state: S.UNSTARTED, video: null, player: null,
        holdState: null, loadMs: 25, clockOffset: 0
    };
    window.__fake = fake;

    function emit(state) {
        fake.state = state;
        if (fake.player && fake.player._events.onStateChange) fake.player._events.onStateChange({ data: state });
    }
    fake.emit = emit;
    fake.end = function () { emit(S.ENDED); };

    // ---- the clock ----
    const realNow = Date.now.bind(Date);
    Date.now = function () { return realNow() + fake.clockOffset; };
    fake.advanceClock = function (ms) { fake.clockOffset += ms; };

    // ---- a load that waits ----
    let heldLoad = null;
    function playAfterLoading(videoId) {
        setTimeout(function () {
            if (fake.video !== videoId) return;   // another video was loaded meanwhile
            emit(S.PLAYING);
        }, fake.loadMs);
    }
    fake.release = function () {
        const load = heldLoad;
        heldLoad = null;
        if (load) { emit(S.BUFFERING); playAfterLoading(load); }
    };

    window.YT = {
        PlayerState: S,
        Player: function (id, opts) {
            const self = this;
            fake.player = self;
            self._events = (opts && opts.events) || {};
            self.loadVideoById = function (videoId) {
                fake.calls.push(['loadVideoById', videoId]);
                fake.loads.push(videoId);
                fake.video = videoId;
                fake.position = 0;
                heldLoad = null;
                if (fake.holdState) {
                    heldLoad = videoId;
                    emit(S[fake.holdState]);
                } else {
                    emit(S.BUFFERING);
                    playAfterLoading(videoId);
                }
            };
            self.getCurrentTime = function () { return fake.position; };
            self.getDuration = function () { return 200; };
            self.seekTo = function (t) { fake.calls.push(['seekTo', t]); fake.seeks.push(t); fake.position = t; };
            self.pauseVideo = function () { fake.calls.push(['pauseVideo']); emit(S.PAUSED); };
            self.playVideo = function () { fake.calls.push(['playVideo']); emit(S.PLAYING); };
            self.stopVideo = function () { fake.calls.push(['stopVideo']); heldLoad = null; emit(S.UNSTARTED); };
            self.mute = function () {};
            self.unMute = function () {};
            self.getPlayerState = function () { return fake.state; };
            self.getVideoData = function () { return { video_id: fake.video }; };
            setTimeout(function () { if (self._events.onReady) self._events.onReady({ target: self }); }, 20);
        }
    };

    // ---- the gate: nothing that decides who plays is asked before the scenario has configured the stand-in ----
    let openGate;
    const gate = new Promise(function (resolve) { openGate = resolve; });
    const realFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
        const url = typeof input === 'string' ? input : (input && input.url) || '';
        if (fake.gated && /\/player-lease(\?|$)/.test(url)) return gate.then(function () { return realFetch(input, init); });
        return realFetch(input, init);
    };

    // ---- the API script ----
    // The script does document.head.appendChild(<script src=".../iframe_api">): do not load the real API, say it is ready.
    fake.gated = /[?&]scenario=/.test(location.search);
    fake.apiRequested = false;
    fake.start = function () {
        fake.gated = false;
        openGate();
        if (fake.apiRequested) {
            fake.apiRequested = false;
            fireApiReady();
        }
    };
    function fireApiReady() {
        if (fake.blockApi) return;
        setTimeout(function () { if (window.onYouTubeIframeAPIReady) window.onYouTubeIframeAPIReady(); }, 30);
    }
    const append = document.head.appendChild.bind(document.head);
    document.head.appendChild = function (node) {
        if (node && node.tagName === 'SCRIPT' && /iframe_api/.test(node.src || '')) {
            if (fake.gated) {
                fake.apiRequested = true;   // fireApiReady when the scenario has configured the stand-in (start)
            } else {
                fireApiReady();
            }
            return node;
        }
        return append(node);
    };
})();
