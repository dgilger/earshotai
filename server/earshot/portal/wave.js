// A dithered sound wave for the portal, drawn on any <canvas class="wave">.
// Calm and grey while the server waits; taller, quicker and green while it transcribes
// (set data-busy on the canvas). Same 8x8 Bayer dither as the app.
(function () {
  const BAYER = [0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21];
  const GREY = { c: [[0x8a, 0x8f, 0x99], [0x3a, 0x3d, 0x45], [0x16, 0x18, 0x1c], [0, 0, 0]], p: [0, 0.45, 0.8, 1] };
  const MINT = { c: [[0xc8, 0xf7, 0xe4], [0x6e, 0xe7, 0xb7], [0x1c, 0x4a, 0x3a], [0, 0, 0]], p: [0, 0.35, 0.8, 1] };
  const CELL = 3;  // css px per dither cell

  // Half height (0..1) of the wave at column fx (0..1), time s (seconds), loudness k (0..1).
  function height(fx, s, k) {
    const env = Math.sin(Math.PI * fx) ** 0.8;  // quiet at both ends
    const w = 0.5 + 0.25 * Math.sin(fx * 23 + s * 2.1) + 0.15 * Math.sin(fx * 51 - s * 3.3)
      + 0.1 * Math.sin(fx * 97 + s * 5.2);
    return env * (0.06 + k * 0.9 * Math.max(0, w));
  }

  const busyLevel = new WeakMap();  // eases between calm and busy
  function draw(cv, s) {
    const w = Math.max(8, Math.round(cv.clientWidth / CELL)), h = Math.max(4, Math.round(cv.clientHeight / CELL));
    if (!cv.clientWidth) return;
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
    const goal = cv.dataset.busy ? 1 : 0;
    let k = busyLevel.get(cv) ?? goal;
    k += (goal - k) * 0.08;
    busyLevel.set(cv, k);
    const pal = k > 0.5 ? MINT : GREY;
    const loud = 0.5 + 0.5 * k, speed = 0.6 + 1.4 * k;
    const ctx = cv.getContext('2d');
    const img = ctx.createImageData(w, h);
    for (let x = 0; x < w; x++) {
      const hh = height((x + 0.5) / w, s * speed, loud);
      for (let y = 0; y < h; y++) {
        const fy = Math.abs((y + 0.5) / h - 0.5) * 2;  // 0 centre .. 1 edge
        if (fy > hh + 0.02) continue;
        const v = Math.min(1, 0.15 + 0.85 * fy / Math.max(hh, 0.02));  // bright core, soft edge
        let i = 0;
        while (i < pal.p.length - 2 && v > pal.p[i + 1]) i++;
        const f = (v - pal.p[i]) / (pal.p[i + 1] - pal.p[i]);
        const c = BAYER[(y & 7) * 8 + (x & 7)] / 64 < f ? pal.c[i + 1] : pal.c[i];
        const o = (y * w + x) * 4;
        img.data[o] = c[0]; img.data[o + 1] = c[1]; img.data[o + 2] = c[2]; img.data[o + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
  }

  const still = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  function frame(t) {
    const s = still ? 1 : t / 1000;
    document.querySelectorAll('canvas.wave').forEach((cv) => draw(cv, s));
    setTimeout(() => requestAnimationFrame(frame), still ? 1000 : 66);
  }
  window.EarshotWave = { draw };
  requestAnimationFrame(frame);
})();
