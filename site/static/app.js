/* AutoVybz landing page – vanilla JS, no third-party code, no tracking. */
(() => {
  "use strict";
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const T = JSON.parse(($("#i18n") || {}).textContent || "{}");
  const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
  };

  /* ---------------------------------------------------------- language */
  // Remember an explicit choice; on the root page, follow the system language once.
  $$("[data-lang]").forEach(a => a.addEventListener("click", () => store.set("avz-lang", a.dataset.lang)));
  (function autoLang() {
    if (!T.langs || T.is404) return;
    const p = location.pathname, root = T.site_path || "/";
    const onRootHome = p === root || p === root + "index.html";
    if (!onRootHome) return;
    const chosen = store.get("avz-lang");
    let target = chosen;
    if (!target) {
      const prefs = (navigator.languages || [navigator.language || ""]).map(l => l.toLowerCase().slice(0, 2));
      target = prefs.find(l => T.langs.includes(l));
    }
    if (target && target !== T.default_lang && T.langs.includes(target)) location.replace(root + target + "/" + location.hash);
  })();

  /* ---------------------------------------------------------- toast + confetti */
  const toastEl = $("#toast");
  let toastTimer;
  function toast(msg) {
    if (!toastEl) return;
    toastEl.textContent = msg;
    toastEl.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove("show"), 2600);
  }
  const cv = $("#confetti");
  function confetti(n = 140) {
    if (!cv || reduceMotion) return;
    const ctx = cv.getContext("2d"), W = (cv.width = innerWidth), H = (cv.height = innerHeight);
    const cols = ["#1EED61", "#FCD207", "#5CF28C", "#ffffff", "#08130C"];
    const parts = Array.from({ length: n }, () => ({
      x: W / 2 + (Math.random() - .5) * W * .3, y: H * .6, vx: (Math.random() - .5) * 16, vy: -Math.random() * 18 - 6,
      s: Math.random() * 8 + 4, r: Math.random() * 6, vr: (Math.random() - .5) * .4, c: cols[(Math.random() * cols.length) | 0],
    }));
    let frames = 0;
    (function tick() {
      ctx.clearRect(0, 0, W, H);
      parts.forEach(p => {
        p.vy += .5; p.vx *= .99; p.x += p.vx; p.y += p.vy; p.r += p.vr;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.r); ctx.fillStyle = p.c; ctx.fillRect(-p.s / 2, -p.s / 4, p.s, p.s / 2); ctx.restore();
      });
      if (++frames < 160) requestAnimationFrame(tick); else ctx.clearRect(0, 0, W, H);
    })();
  }

  /* ---------------------------------------------------------- clipboard */
  async function copyText(text) {
    try { await navigator.clipboard.writeText(text); return true; } catch {
      const ta = document.createElement("textarea");
      ta.value = text; ta.setAttribute("readonly", ""); ta.style.position = "fixed"; ta.style.opacity = "0";
      document.body.appendChild(ta); ta.select();
      let ok = false; try { ok = document.execCommand("copy"); } catch { /* ignore */ }
      ta.remove(); return ok;
    }
  }
  $$("[data-copy]").forEach(b => b.addEventListener("click", async () => {
    const ok = await copyText(b.dataset.copy);
    const old = b.textContent;
    b.textContent = ok ? T.copied : "✗";
    toast(ok ? T.copied : T.copy_fail);
    setTimeout(() => (b.textContent = old), 1800);
  }));

  /* ---------------------------------------------------------- share */
  const shareText = `${T.share_text} ${T.share_tags}`;
  $$("[data-copy-link]").forEach(b => b.addEventListener("click", async () => {
    const ok = await copyText(`${shareText} ${T.site_url}`);
    toast(ok ? T.link_copied : T.copy_fail); if (ok) confetti(90);
  }));
  $$("[data-share-native]").forEach(b => {
    if (!navigator.share) return;
    b.hidden = false;
    b.addEventListener("click", () => navigator.share({ title: "AutoVybz", text: shareText, url: T.site_url }).then(() => confetti()).catch(() => {}));
  });
  $$("[data-share]").forEach(a => a.addEventListener("click", () => confetti(60)));

  /* ---------------------------------------------------------- reveal, count-up, sticky */
  const io = "IntersectionObserver" in window ? new IntersectionObserver(es => es.forEach(e => {
    if (!e.isIntersecting) return;
    e.target.classList.add("in");
    $$("[data-countup]", e.target).forEach(countUp);
    io.unobserve(e.target);
  }), { threshold: .15 }) : null;
  $$(".reveal").forEach(el => io ? io.observe(el) : el.classList.add("in"));

  function countUp(el) {
    const raw = el.dataset.countup, m = raw.match(/^(\D*)(\d+)(.*)$/);
    if (!m || reduceMotion) return;
    const [, pre, num, post] = m, target = +num, t0 = performance.now();
    (function f(t) {
      const k = Math.min(1, (t - t0) / 1100), v = Math.round(target * (1 - Math.pow(1 - k, 3)));
      el.textContent = pre + v + post;
      if (k < 1) requestAnimationFrame(f);
    })(t0);
  }
  const cd = $("[data-countdown]");
  if (cd && !reduceMotion) { let i = 0; const iv = setInterval(() => { cd.textContent = String(++i); if (i >= 10) clearInterval(iv); }, 70); }

  const sticky = $(".sticky-cta"), hero = $(".hero"), getSec = $("#get");
  if (sticky && hero) addEventListener("scroll", () => {
    const pastHero = scrollY > hero.offsetHeight * .8;
    const atGet = getSec && getSec.getBoundingClientRect().top < innerHeight && getSec.getBoundingClientRect().bottom > 0;
    sticky.classList.toggle("show", pastHero && !atGet);
  }, { passive: true });

  /* ---------------------------------------------------------- phone mockup loop */
  const phState = $("[data-phone-state]"), phTimer = $("[data-phone-timer]");
  if (phState && phTimer && !reduceMotion) {
    let s = 0;
    setInterval(() => {
      s = (s + 1) % 16; // 0-2 idle, 3-12 measuring (10 s), 13-15 done
      if (s < 3) { phState.textContent = phState.dataset.l1; phTimer.textContent = "10 s"; }
      else if (s <= 12) { phState.textContent = phState.dataset.l2; phTimer.textContent = (13 - s) + " s"; }
      else { phState.textContent = phState.dataset.l3; phTimer.textContent = "✓"; }
    }, 1000);
  }

  /* ---------------------------------------------------------- tab title trick */
  const origTitle = document.title;
  document.addEventListener("visibilitychange", () => { document.title = document.hidden && T.tab_away ? T.tab_away : origTitle; });

  /* ---------------------------------------------------------- easter egg: konami / "vybz" / "bass" */
  const konami = ["ArrowUp", "ArrowUp", "ArrowDown", "ArrowDown", "ArrowLeft", "ArrowRight", "ArrowLeft", "ArrowRight", "b", "a"];
  let keys = [], typed = "";
  addEventListener("keydown", e => {
    if (e.target.closest("input, textarea")) return;
    keys = [...keys, e.key].slice(-konami.length);
    typed = (typed + (e.key.length === 1 ? e.key.toLowerCase() : "")).slice(-8);
    if (keys.join() === konami.join() || typed.endsWith("vybz") || typed.endsWith("bass")) { bassMode(); keys = []; typed = ""; }
  });
  function bassMode() {
    document.body.classList.remove("bass"); void document.body.offsetWidth; document.body.classList.add("bass");
    confetti(220); toast(T.bass_mode);
    if (demo.ctx && demo.playing) bassDrop();
  }

  /* ========================================================== DEMO ENGINE */
  // Simulated "small speaker in a room" and an AutoVybz-style correction (what the app's fitter does for such a speaker).
  const SPEAKER = [
    { type: "highpass", f: 72, q: 0, g: 0 },
    { type: "peaking", f: 115, q: 1.4, g: 8 },
    { type: "peaking", f: 420, q: 1.6, g: 3.5 },
    { type: "peaking", f: 3200, q: 2.2, g: 6 },
    { type: "highshelf", f: 7500, q: 0, g: -8 },
  ];
  const FIX = [
    { type: "peaking", f: 115, q: 1.4, g: -7 },
    { type: "peaking", f: 420, q: 1.6, g: -3.5 },
    { type: "peaking", f: 3200, q: 2.2, g: -5.8 },
    { type: "highshelf", f: 7500, q: 0, g: 6 },
    { type: "lowshelf", f: 100, q: 0, g: 2.5 },
  ];
  const TARGET = [{ type: "lowshelf", f: 105, q: 0, g: 3.5 }];

  // The demo plays a real recording (site/static/audio/demosong.mp3 – the demo song, not a synth loop:
  // square-wave "instruments" sound nothing like music and made the A/B comparison meaningless). The
  // file is routed through the modelled speaker + correction chain, so switching A/B changes exactly
  // what it changes in the app. `preload="none"`: nothing is downloaded until the visitor presses play.
  const demo = { ctx: null, on: false, playing: false, src: null, wobble: null, fallback: false };
  const audioEl = $("#demo-audio");
  const canvas = $("#demo-canvas");
  const NPTS = 240, FREQS = new Float32Array(NPTS);
  for (let i = 0; i < NPTS; i++) FREQS[i] = 20 * Math.pow(1000, i / (NPTS - 1));

  function responseDb(ctx, chain) {
    const out = new Float32Array(NPTS).fill(0), mag = new Float32Array(NPTS), ph = new Float32Array(NPTS);
    chain.forEach(c => {
      const b = ctx.createBiquadFilter();
      b.type = c.type; b.frequency.value = c.f; b.Q.value = c.q; b.gain.value = c.g;
      b.getFrequencyResponse(FREQS, mag, ph);
      for (let i = 0; i < NPTS; i++) out[i] += 20 * Math.log10(Math.max(mag[i], 1e-6));
    });
    return out;
  }
  const bandMean = (arr, lo, hi) => { let s = 0, n = 0; for (let i = 0; i < NPTS; i++) if (FREQS[i] >= lo && FREQS[i] <= hi) { s += arr[i]; n++; } return s / n; };

  let curves = null;
  function computeCurves() {
    const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    if (!OAC) return null;
    const oc = new OAC(1, 128, 44100);
    const raw = responseDb(oc, SPEAKER), fix = responseDb(oc, FIX), tgt = responseDb(oc, TARGET);
    const nRaw = bandMean(raw, 300, 3000);
    const rawN = raw.map(v => v - nRaw);
    const both = raw.map((v, i) => v + fix[i]);
    const nBoth = bandMean(both, 300, 3000);
    // level-match: corrected path is trimmed so both versions are equally loud in the 100 Hz – 8 kHz band
    const trimDb = bandMean(raw, 100, 8000) - bandMean(both, 100, 8000);
    return { rawN, fixN: both.map(v => v - nBoth), tgt, trimDb };
  }

  function draw(spectrum) {
    if (!canvas || !curves) return;
    const c = canvas.getContext("2d"), W = canvas.width, H = canvas.height, padL = 46, padB = 28, padT = 14;
    const x = f => padL + (Math.log10(f / 20) / 3) * (W - padL - 10);
    const y = db => padT + ((12 - db) / 30) * (H - padT - padB);
    c.clearRect(0, 0, W, H);
    c.font = `600 ${W < 700 ? 17 : 18}px system-ui, sans-serif`; c.fillStyle = "#828F9A"; c.strokeStyle = "#2E3843"; c.lineWidth = 1;
    (W < 700 ? [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000] : [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]).forEach(f => {
      c.beginPath(); c.moveTo(x(f), padT); c.lineTo(x(f), H - padB); c.stroke();
      const lbl = f >= 1000 ? f / 1000 + "k" : String(f); c.fillText(lbl, Math.min(x(f) - 12, W - 10 - c.measureText(lbl).width), H - 6);
    });
    [12, 6, 0, -6, -12, -18].forEach(db => {
      c.strokeStyle = db === 0 ? "#3D4955" : "#28323D"; c.beginPath(); c.moveTo(padL, y(db)); c.lineTo(W - 10, y(db)); c.stroke();
      c.fillText((db > 0 ? "+" : "") + db, 4, y(db) + 6);
    });
    if (spectrum) { // live analyser bars
      const n = spectrum.length, sr = demo.ctx.sampleRate;
      c.fillStyle = demo.on ? "rgba(92,242,140,.16)" : "rgba(252,210,7,.12)";
      for (let i = 2; i < n; i += 2) {
        const f = (i * sr) / (2 * n); if (f < 20 || f > 20000) continue;
        const v = (spectrum[i] + 100) / 70, h = Math.max(0, v) * (H - padT - padB) * .9;
        c.fillRect(x(f), H - padB - h, Math.max(2, x(f * 1.04) - x(f)), h);
      }
    }
    const line = (arr, color, width, dash) => {
      c.save(); c.strokeStyle = color; c.lineWidth = width; c.setLineDash(dash || []); c.lineJoin = "round"; c.beginPath();
      for (let i = 0; i < NPTS; i++) { const px = x(FREQS[i]), py = y(Math.max(-18, Math.min(12, arr[i]))); i ? c.lineTo(px, py) : c.moveTo(px, py); }
      c.stroke(); c.restore();
    };
    line(curves.tgt, "#909CA6", 2, [8, 8]);
    line(curves.rawN, demo.on ? "rgba(252,210,7,.35)" : "#FCD207", demo.on ? 2.5 : 5);
    line(curves.fixN, demo.on ? "#5CF28C" : "rgba(92,242,140,.35)", demo.on ? 5 : 2.5);
  }

  function initAudio() {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return false;
    const ctx = new AC();
    demo.ctx = ctx;
    demo.bus = ctx.createGain(); demo.bus.gain.value = .55;
    let node = demo.bus;
    SPEAKER.forEach(s => { const b = ctx.createBiquadFilter(); b.type = s.type; b.frequency.value = s.f; b.Q.value = s.q; b.gain.value = s.g; node.connect(b); node = b; });
    demo.fixNodes = FIX.map(s => { const b = ctx.createBiquadFilter(); b.type = s.type; b.frequency.value = s.f; b.Q.value = s.q; b.gain.value = 0; node.connect(b); node = b; return b; });
    demo.trim = ctx.createGain(); node.connect(demo.trim);
    // Easter-egg "wobble": a short low-shelf lift on the *real* recording (the old synth dropped a kick).
    demo.wobble = ctx.createBiquadFilter(); demo.wobble.type = "lowshelf";
    demo.wobble.frequency.value = 90; demo.wobble.gain.value = 0; demo.trim.connect(demo.wobble);
    demo.vol = ctx.createGain(); demo.wobble.connect(demo.vol);
    const comp = ctx.createDynamicsCompressor(); comp.threshold.value = -6; comp.ratio.value = 8; comp.attack.value = .003; comp.release.value = .2;
    demo.vol.connect(comp);
    demo.an = ctx.createAnalyser(); demo.an.fftSize = 4096; demo.an.smoothingTimeConstant = .82;
    comp.connect(demo.an); demo.an.connect(ctx.destination);
    if (audioEl) {
      try {
        demo.src = ctx.createMediaElementSource(audioEl);
        demo.src.connect(demo.bus);
      } catch {
        // Some browsers refuse a media-element source (older iOS/Safari, restricted contexts). The demo
        // then plays the raw element - audible, but without the simulated speaker: never silently broken.
        demo.src = null;
        demo.fallback = true;
      }
    }
    setVolume(); applyAB(true);
    return true;
  }
  function setVolume() {
    const v = +$("#demo-vol").value / 100;
    if (demo.fallback && audioEl) { audioEl.volume = Math.min(1, v * v * 1.2); return; }
    if (demo.vol) demo.vol.gain.setTargetAtTime(v * v * 1.6, demo.ctx.currentTime, .03);
  }
  function bassDrop() {
    if (!demo.wobble) return;
    const t = demo.ctx.currentTime;
    demo.wobble.gain.cancelScheduledValues(t);
    demo.wobble.gain.setValueAtTime(0, t);
    demo.wobble.gain.linearRampToValueAtTime(14, t + .05);
    demo.wobble.gain.setTargetAtTime(0, t + .35, .25);
  }
  function applyAB(instant) {
    $("#ab-off")?.setAttribute("aria-pressed", String(!demo.on));
    $("#ab-on")?.setAttribute("aria-pressed", String(demo.on));
    if (demo.ctx && curves) {
      const t = demo.ctx.currentTime, tc = instant ? .001 : .06;
      demo.fixNodes.forEach((n, i) => n.gain.setTargetAtTime(demo.on ? FIX[i].g : 0, t, tc));
      demo.trim.gain.setTargetAtTime(demo.on ? Math.pow(10, curves.trimDb / 20) : 1, t, tc);
    }
    if (!demo.playing) draw();
  }

  // ---- transport: the real recording through the model
  function frame() {
    if (!demo.playing || !demo.an) return;
    const data = new Float32Array(demo.an.frequencyBinCount); demo.an.getFloatFrequencyData(data);
    draw(data); requestAnimationFrame(frame);
  }
  function setPlayUI(playing) {
    const btn = $("#demo-play");
    demo.playing = playing;
    if (!btn) return;
    btn.querySelector(".ico").textContent = playing ? "❚❚" : "▶";
    btn.querySelector(".lbl").textContent = playing ? btn.dataset.pause : btn.dataset.play;
    btn.setAttribute("aria-pressed", String(playing));
  }
  async function togglePlay() {
    if (!audioEl) return toast(T.audio_fail);
    if (!demo.ctx && !initAudio()) return toast(T.audio_fail);
    if (demo.ctx.state !== "running") { try { await demo.ctx.resume(); } catch { return toast(T.audio_fail); } }
    if (demo.playing) {
      audioEl.pause();
      setPlayUI(false);
      draw();
      return;
    }
    try {
      await audioEl.play();
    } catch {
      // Autoplay policy, missing codec or a 404 on the file - tell the visitor instead of a silent button.
      return toast(audioEl.dataset.error || T.audio_fail);
    }
    setPlayUI(true);
    applyAB(true);
    frame();
  }
  if (canvas) {
    // phones: fewer, larger pixels so the axis labels stay readable
    if (canvas.clientWidth && canvas.clientWidth < 640) { canvas.width = 560; canvas.height = 400; }
    curves = computeCurves();
    draw();
    $("#demo-play")?.addEventListener("click", togglePlay);
    $("#ab-off")?.addEventListener("click", () => { demo.on = false; applyAB(); });
    $("#ab-on")?.addEventListener("click", () => { demo.on = true; applyAB(); if (!demo.flipped) { demo.flipped = true; confetti(70); } });
    $("#demo-vol")?.addEventListener("input", setVolume);
    audioEl?.addEventListener("play", () => setPlayUI(true));
    audioEl?.addEventListener("pause", () => { setPlayUI(false); draw(); });
    audioEl?.addEventListener("error", () => toast(audioEl.dataset.error || T.audio_fail));
    // key "A" flips A/B while playing – instant comparison
    addEventListener("keydown", e => { if (e.code === "KeyA" && demo.playing && !e.target.closest("input,textarea")) { demo.on = !demo.on; applyAB(); } });
  }

  /* ========================================================== SPEAKER ROAST */
  const BASE = { corner: 34, wall: 52, shelf: 44, table: 49, floor: 41, free: 68 };
  const DELTA = { bt: -9, bar: -5, desk: -6, party: -12, hifi: 9 };
  const pick = { type: null, place: null };
  $$(".chips").forEach(group => group.addEventListener("click", e => {
    const chip = e.target.closest(".chip"); if (!chip) return;
    $$(".chip", group).forEach(c => c.setAttribute("aria-pressed", String(c === chip)));
    pick[group.dataset.group] = chip.dataset.value;
  }));
  $$(".chip").forEach(c => c.setAttribute("aria-pressed", "false"));
  let lastRoast = null;
  $("#roast-go")?.addEventListener("click", () => {
    if (!pick.type || !pick.place) return toast(T.roast_pick);
    const d = T.diag[pick.place], score = Math.max(8, Math.min(95, BASE[pick.place] + DELTA[pick.type]));
    lastRoast = { score, title: d.t, desc: `${d.d} ${T.types[pick.type]}` };
    const res = $("#roast-result"); res.hidden = false;
    $("#roast-title").textContent = lastRoast.title;
    $("#roast-desc").textContent = lastRoast.desc;
    const fg = $("#gauge-fg"); fg.style.stroke = score < 40 ? "#FF8A8A" : score < 60 ? "#FCD207" : "#5CF28C";
    fg.style.strokeDashoffset = String(327 * (1 - score / 100));
    const num = $("#roast-score"), t0 = performance.now();
    (function f(t) { const k = Math.min(1, (t - t0) / 1100); num.textContent = String(Math.round(score * k)); if (k < 1) requestAnimationFrame(f); })(t0);
    res.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "center" });
    if (score < 50) document.body.classList.add("bass"), setTimeout(() => document.body.classList.remove("bass"), 1600);
  });
  $("#roast-again")?.addEventListener("click", () => {
    $("#roast-result").hidden = true; $$(".chip").forEach(c => c.setAttribute("aria-pressed", "false")); pick.type = pick.place = null;
    $("#roast").scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth" });
  });
  const roastShareText = () => T.roast_share.replace("{score}", lastRoast.score).replace("{title}", lastRoast.title) + ` ${T.site_url} ${T.share_tags}`;
  $("#roast-share")?.addEventListener("click", async () => {
    if (!lastRoast) return;
    const text = roastShareText();
    if (navigator.share) {
      try {
        const file = await cardFile();
        const data = file && navigator.canShare && navigator.canShare({ files: [file] }) ? { files: [file], text } : { text };
        await navigator.share(data); confetti(); return;
      } catch (e) { if (e && e.name === "AbortError") return; }
    }
    const ok = await copyText(text); toast(ok ? T.link_copied : T.copy_fail); if (ok) confetti(90);
  });
  $("#roast-card")?.addEventListener("click", async () => {
    const file = await cardFile(); if (!file) return;
    const a = document.createElement("a"); a.href = URL.createObjectURL(file); a.download = file.name; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000); confetti(80);
  });

  function loadImg(src) { return new Promise((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = no; i.src = src; }); }
  async function cardFile() {
    if (!lastRoast) return null;
    const S = 1080, c = document.createElement("canvas"); c.width = c.height = S; const g = c.getContext("2d");
    const bg = g.createRadialGradient(S * .75, S * .2, 50, S * .75, S * .2, S); bg.addColorStop(0, "#123A27"); bg.addColorStop(1, "#161C21");
    g.fillStyle = bg; g.fillRect(0, 0, S, S);
    g.fillStyle = "#FCD207"; g.beginPath(); g.moveTo(S - 260, S); g.lineTo(S - 170, S); g.lineTo(S, S - 170); g.lineTo(S, S - 260); g.fill();
    g.fillStyle = "#1EED61"; g.beginPath(); g.moveTo(S - 170, S); g.lineTo(S - 80, S); g.lineTo(S, S - 80); g.lineTo(S, S - 170); g.fill();
    try { const logo = await loadImg($(".brand img").src.replace("logo-128", "logo-256")); g.drawImage(logo, 70, 60, 120, 120); } catch { /* ignore */ }
    try { await Promise.all(["900 56px Outfit", "500 32px Outfit"].map(f => document.fonts.load(f))); } catch { /* system font fallback */ }
    const font = w => `${w} Outfit, system-ui, -apple-system, Segoe UI, Roboto, sans-serif`;
    g.fillStyle = "#fff"; g.font = font("900 56px"); g.fillText("AutoVybz", 210, 140);
    g.fillStyle = "#FCD207"; g.font = font("800 40px"); g.fillText("🔥 Speaker Roast", 70, 270);
    // gauge
    const cx = S / 2, cy = 470, r = 150, sc = lastRoast.score;
    g.lineWidth = 26; g.strokeStyle = "#313C47"; g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.stroke();
    g.strokeStyle = sc < 40 ? "#FF8A8A" : sc < 60 ? "#FCD207" : "#5CF28C"; g.lineCap = "round";
    g.beginPath(); g.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * sc / 100); g.stroke();
    g.fillStyle = "#fff"; g.textAlign = "center"; g.font = font("900 120px"); g.fillText(String(sc), cx, cy + 40);
    g.font = font("700 30px"); g.fillStyle = "#A9B4BE"; g.fillText("/ 100", cx, cy + 90);
    g.fillStyle = "#FCD207"; g.font = font("900 64px"); g.fillText(lastRoast.title, cx, 720);
    g.fillStyle = "#DDE3E7"; g.font = font("500 32px");
    wrapText(g, lastRoast.desc, cx, 785, S - 180, 44, 4);
    g.textAlign = "left"; g.fillStyle = "#5CF28C"; g.font = font("900 40px"); g.fillText(T.card_fix + " →", 70, S - 110);
    g.fillStyle = "#FCD207"; g.font = font("700 32px"); g.fillText(T.card_url, 70, S - 60);
    const blob = await new Promise(ok => c.toBlob(ok, "image/png"));
    return blob ? new File([blob], "autovybz-speaker-roast.png", { type: "image/png" }) : null;
  }
  function wrapText(g, text, x, y, maxW, lh, maxLines) {
    const lines = [];
    let line = "";
    for (const w of text.split(" ")) {
      const test = line ? line + " " + w : w;
      if (g.measureText(test).width > maxW && line) { lines.push(line); line = w; } else line = test;
    }
    if (line) lines.push(line);
    lines.slice(0, maxLines).forEach((l, i) => {
      const last = i === maxLines - 1 && lines.length > maxLines;
      g.fillText(last ? l.replace(/[ ,.;:]*$/, "") + "…" : l, x, y + i * lh);
    });
  }
})();
