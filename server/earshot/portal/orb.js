// The Earshot orb: a dithered ball that slowly spins, the same as the record button in the app.
// Drawn on any <canvas class="orb">: data-cells = ball size in dither cells, data-rec = red.
(function () {
  const BAYER = [0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21];
  const GREY = { c: [[0xed, 0xed, 0xed], [0x8a, 0x8f, 0x99], [0x26, 0x28, 0x2e], [0, 0, 0]], p: [0, 0.4, 0.82, 1] };
  const RED = { c: [[0xff, 0x7a, 0x70], [0xff, 0x45, 0x3a], [0x5a, 0x14, 0x10], [0, 0, 0]], p: [0, 0.35, 0.8, 1] };
  // Darkness 0..1 of the ball at cell centre (fx, fy) in 0..1, turned by `angle` around the
  // vertical axis; -1 outside the ball. Same maths as RecordDial in the app: the old still orb
  // (light spot upper left, dark rim) plus soft bands on the surface that move as it turns.
  function shade(fx, fy, angle) {
    const x = fx * 2 - 1, y = fy * 2 - 1, d = x * x + y * y;
    if (d > 1) return -1;
    const z = Math.sqrt(1 - d);
    let t = Math.hypot(fx - 0.375, fy - 0.35) / 0.625;
    const lon = Math.atan2(x, z) + angle;
    t += 0.13 * Math.sin(5 * lon) * Math.sqrt(1 - y * y) * z;
    return Math.min(1, Math.max(0, t));
  }

  function draw(cv, angle) {
    const n = +cv.dataset.cells || 40;
    const pal = cv.dataset.rec ? RED : GREY;
    if (cv.width !== n) { cv.width = n; cv.height = n; }
    const ctx = cv.getContext('2d');
    const img = ctx.createImageData(n, n);
    for (let yy = 0; yy < n; yy++) for (let xx = 0; xx < n; xx++) {
      const v = shade((xx + 0.5) / n, (yy + 0.5) / n, angle);
      const i = (yy * n + xx) * 4;
      if (v < 0) continue;
      let k = 0;
      while (k < pal.p.length - 2 && v > pal.p[k + 1]) k++;
      const f = (v - pal.p[k]) / Math.max(1e-6, pal.p[k + 1] - pal.p[k]);
      const c = BAYER[(yy & 7) * 8 + (xx & 7)] / 64 < f ? pal.c[k + 1] : pal.c[k];
      img.data[i] = c[0]; img.data[i + 1] = c[1]; img.data[i + 2] = c[2]; img.data[i + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
  }

  const still = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  const PERIOD = 9000;  // ms per turn: slow and calm
  let t0 = null;
  function frame(t) {
    if (t0 === null) t0 = t;
    const a = still ? 0.6 : ((t - t0) / PERIOD) * 2 * Math.PI + 0.6;
    document.querySelectorAll('canvas.orb').forEach((cv) => draw(cv, a));
    if (!still) setTimeout(() => requestAnimationFrame(frame), 66);  // ~15 fps is plenty for a dither
  }
  window.EarshotOrb = { shade, draw };
  requestAnimationFrame(frame);
})();
