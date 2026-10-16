package eu.kanade.tachiyomi.ui.stream

/** Scripts injected into the YouTube / YouTube Music pages (idempotent, safe to run repeatedly). */
internal object StreamScripts {

    const val BRIDGE = "KumaStream"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Mobile Safari/537.36"

    val MAIN = """
(function () {
  if (window.__kumaStream) return;
  window.__kumaStream = true;

  // ---- ad removal: strip ad fields from player/browse JSON ----
  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  function strip(o) {
    try {
      if (o && typeof o === 'object') {
        AD_KEYS.forEach(function (k) { if (k in o) delete o[k]; });
        if (o.playerResponse) strip(o.playerResponse);
        if (Array.isArray(o)) o.forEach(function (i) { if (i && i.playerResponse) strip(i.playerResponse); });
      }
    } catch (e) {}
    return o;
  }
  var _parse = JSON.parse;
  JSON.parse = function () { return strip(_parse.apply(this, arguments)); };
  var _json = Response.prototype.json;
  Response.prototype.json = function () { return _json.apply(this, arguments).then(strip); };
  ['ytInitialPlayerResponse'].forEach(function (name) {
    var v = window[name];
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get: function () { return v; },
        set: function (x) { v = strip(x); }
      });
    } catch (e) {}
  });

  // ---- keep playing while the app is in the background ----
  try {
    Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return false; } });
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return 'visible'; } });
    document.addEventListener('visibilitychange', function (e) { e.stopImmediatePropagation(); }, true);
  } catch (e) {}

  // ---- YouTube (not Music): open videos in the app's native player ----
  if (location.hostname.indexOf('music.') !== 0) {
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
      if (!a) return;
      var h = a.href || '';
      if (!/youtube\.com\/(watch\?|shorts\/)|youtu\.be\//.test(h)) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      try { window.$BRIDGE.openVideo(h); } catch (x) {}
    }, true);
  }

  // ---- report play state to the native side (foreground service) ----
  var last = null;
  function report() {
    var v = document.querySelector('video');
    var playing = !!v && !v.paused && !v.ended;
    if (playing !== last) {
      last = playing;
      try { window.$BRIDGE.onPlaying(playing); } catch (e) {}
    }
  }
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (ev) {
    document.addEventListener(ev, report, true);
  });

  // ---- skip ads / dismiss "continue watching" ----
  setInterval(function () {
    var v = document.querySelector('video');
    var ad = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay');
    if (ad && v && isFinite(v.duration) && v.duration > 0) {
      v.muted = true;
      v.currentTime = v.duration;
    }
    var skip = document.querySelector(
      '.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-container button'
    );
    if (skip) skip.click();
    var dlg = document.querySelector('ytmusic-you-there-renderer, ytm-confirm-dialog-renderer');
    if (dlg) {
      var b = dlg.querySelectorAll('button');
      if (b.length === 1) b[0].click();
      if (v && v.paused && last === true) v.play();
    }
    report();
  }, 700);

  // ---- hide ad containers ----
  var st = document.createElement('style');
  st.textContent =
    'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-slot,ad-slot-renderer,' +
    'ytm-ad-slot-renderer,ytm-statement-banner-renderer,.ytp-ad-module,#player-ads,.masthead-ad-control,' +
    'ytd-ad-slot-renderer,ytmusic-mealbar-promo-renderer,ytmusic-statement-banner-renderer,' +
    'ytm-mealbar-promo-renderer{display:none!important}';
  (document.head || document.documentElement).appendChild(st);
})();
""".trimIndent()
}
